package org.roguenet.simpler;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.samskivert.util.Logger;
import com.samskivert.util.StringUtil;
import com.threerings.servlet.util.Parameters;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public abstract class SimplerServlet extends HttpServlet {
    public SimplerServlet (String baseEndpoint, Gson gson) {
        _baseEndpoint = baseEndpoint;
        _gson = gson;

        for (Method method : getClass().getDeclaredMethods()) {
            Map<String, RestMethod> methodMap = null;
            String responseName = null;
            if (method.isAnnotationPresent(RestGet.class)) {
                if (method.getParameterTypes().length > 0) {
                    log.warning("GET method has parameter types, unexpected", "method", method);
                }
                methodMap = _gets;
                responseName = method.getAnnotation(RestGet.class).name();

            } else if (method.isAnnotationPresent(RestPost.class)) {
                methodMap = _posts;
                responseName = method.getAnnotation(RestPost.class).name();

            } else if (method.isAnnotationPresent(RestDelete.class)) {
                methodMap = _deletes;
                responseName = method.getAnnotation(RestDelete.class).name();
            }
            if (responseName == null || responseName.isEmpty()) responseName = null;
            if (methodMap != null) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 0 && parameters.length != 1) {
                    log.warning("Method has more than one parameter", "method", method);
                }
                Class<?> reqParam = parameters.length == 0 ? null : parameters[0];
                String contentType = null;
                if (method.isAnnotationPresent(NotJson.class)) {
                    contentType = method.getAnnotation(NotJson.class).contentType();
                }
                methodMap.put(method.getName(),
                    new RestMethod(method, reqParam, responseName, contentType));
            }
        }
    }

    public String getBaseEndpoint () { return _baseEndpoint; }

    @Override
    protected final void doGet (HttpServletRequest req, HttpServletResponse rsp)
        throws IOException, ServletException {
        if (!handleRequest(req, rsp, _gets)) super.doGet(req, rsp);
    }

    @Override
    protected final void doPost (HttpServletRequest req, HttpServletResponse rsp)
        throws IOException, ServletException {
        if (!handleRequest(req, rsp, _posts)) super.doPost(req, rsp);
    }

    @Override protected void doDelete (HttpServletRequest req, HttpServletResponse rsp)
        throws ServletException, IOException {
        if (!handleRequest(req, rsp, _deletes)) super.doDelete(req, rsp);
    }

    protected final boolean handleRequest (HttpServletRequest req, HttpServletResponse rsp,
        Map<String, RestMethod> methodMap) throws IOException {
        String methodName = getMethodName(req);
        RestMethod method = methodMap.get(methodName);
        String pathInfo = req.getPathInfo();
        _pathInfo.set(pathInfo == null ? "" : req.getPathInfo().substring(methodName.length() + 1));
        _req.set(req);
        _rsp.set(rsp);
        _params.set(new Parameters(req));
        if (method == null) {
            method = checkDefaultMethods(methodName, methodMap);
            if (method == null) return false;
        }

        rsp.setHeader("Cache-Control", "no-cache");
        rsp.setHeader("Content-Type",
            method.contentType == null ? "application/json" : method.contentType);
        boolean usedWriter = false;
        try {
            Object response;
            if (method.requestClass != null) {
                Object param = _gson.fromJson(
                    new InputStreamReader(req.getInputStream()), method.requestClass);
                if (param == null) {
                    throw new RestException("Missing request data");
                }
                response = method.method.invoke(this, param);
            } else {
                response = method.method.invoke(this);
            }

            // methods with a specific contentType handle their own response writing
            if (method.contentType == null) {
                if (response == null) {
                    response = new JsonObject();
                    // default to an empty json object instead of "null", friendlier for the client
                }
                if (method.responseName != null) {
                    if (!(response instanceof JsonElement)) {
                        response = _gson.toJsonTree(response);
                    }
                    JsonObject json = new JsonObject();
                    json.add(method.responseName, (JsonElement)response);
                    response = json;
                }
                usedWriter = true;
                if (response instanceof JsonElement) {
                    _gson.toJson((JsonElement)response, rsp.getWriter());
                } else {
                    _gson.toJson(response, rsp.getWriter());
                }
            }
        } catch (IllegalAccessException iae) {
            usedWriter = true;
            doUnexpectedFailure(iae);
        } catch (InvocationTargetException ite) {
            usedWriter = true;
            if (ite.getCause() instanceof RestException) {
                ((RestException)ite.getCause()).write(_gson, rsp);
            } else {
                doUnexpectedFailure(ite.getCause());
            }
        } catch (RestException de) {
            usedWriter = true;
            de.write(_gson, rsp);
        }
        if (usedWriter) {
            rsp.getWriter().flush();
            rsp.getWriter().close();
        }
        return true;
    }

    protected String getMethodName (HttpServletRequest req) {
        String pathInfo = req.getPathInfo();
        if (StringUtil.isBlank(pathInfo)) return "";
        int idx = pathInfo.indexOf('/', 1);
        return pathInfo.substring(1, idx < 0 ? pathInfo.length() : idx);
    }

    protected RestMethod checkDefaultMethods (String name, Map<String, RestMethod> map) {
        RestMethod method = null;
        if (name.length() == 0) {
            method = map.get(DEFAULT_FIND_ALL);
        }
        if (method == null) {
            // TODO: receive the request method, find only is valid for GET, etc.
            method = map.get(DEFAULT_FIND);
        }

        if (method != null) {
            String pathInfo = _req.get().getPathInfo();
            _pathInfo.set(pathInfo == null ? "" : _req.get().getPathInfo().substring(1));
        }

        return method;
    }

    protected void doUnexpectedFailure (Throwable e) {
        String path = _req.get().getServletPath();
        if (e instanceof IllegalStateException && "STREAM".equals(e.getMessage())) {
            log.info("Servlet response stream unavailable", "servlet", path);
            return; // no need to write a 500 response, our output stream is hosed
        }

        log.warning("unexpected failure", e);
        try {
            new RestException(e.getMessage()).write(_gson, _rsp.get());
        } catch (IOException ioe) {
            log.warning("ioe attempting to send error message", ioe);
        }
    }

    protected static class RestMethod {
        public final Method method;
        public final Class<?> requestClass;
        public final String responseName;
        public final String contentType;

        public RestMethod (Method method, Class<?> requestClass, String responseName,
            String contentType) {
            this.method = method;
            this.requestClass = requestClass;
            this.responseName = responseName;
            this.contentType = contentType;
        }
    }

    protected static final String DEFAULT_FIND_ALL = "_findAll";
    protected static final String DEFAULT_FIND = "_find";

    protected static final Logger log = Logger.getLogger(SimplerServlet.class);

    protected String _baseEndpoint;
    protected Gson _gson;

    protected final ThreadLocal<HttpServletRequest> _req = new ThreadLocal<HttpServletRequest>();
    protected final ThreadLocal<HttpServletResponse> _rsp = new ThreadLocal<HttpServletResponse>();
    protected final ThreadLocal<Parameters> _params = new ThreadLocal<Parameters>();
    protected final ThreadLocal<String> _pathInfo = new ThreadLocal<String>();

    protected final Map<String, RestMethod> _gets = new HashMap<String, RestMethod>();
    protected final Map<String, RestMethod> _posts = new HashMap<String, RestMethod>();
    protected final Map<String, RestMethod> _deletes = new HashMap<String, RestMethod>();
}
