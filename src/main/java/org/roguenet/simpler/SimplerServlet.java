package org.roguenet.simpler;

import com.google.common.base.Function;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.samskivert.util.Logger;
import com.samskivert.util.StringUtil;
import com.threerings.servlet.util.Converters;
import com.threerings.servlet.util.Parameters;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.roguenet.simpler.util.RequestLocal;
import react.UnitSignal;

public abstract class SimplerServlet extends HttpServlet {
    public SimplerServlet (String baseEndpoint, Gson gson) {
        this(baseEndpoint, gson, new ThreadLocal<UnitSignal>() {
            @Override protected UnitSignal initialValue () {
                return new UnitSignal();
            }
        });
    }

    public SimplerServlet (String baseEndpoint, Gson gson, ThreadLocal<UnitSignal> reset) {
        _reset = reset;
        if (_reset.get() == null) {
            log.error("The reset ThreadLocal must be configured with initialValue.");
        }
        _baseEndpoint = baseEndpoint;
        _gson = gson;

        _req = new RequestLocal<HttpServletRequest>(_reset);
        _rsp = new RequestLocal<HttpServletResponse>(_reset);
        _params = new RequestLocal<Parameters>(_reset);
        _pathInfo = new RequestLocal<String>(_reset);

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
                boolean mt = methodIsMicrotome(method);
                if (method.isAnnotationPresent(NotJson.class)) {
                    if (mt) {
                        log.warning("Method has both @Microtome and @NotJson. @NotJson ignored.",
                            "method", method);
                    } else {
                        contentType = method.getAnnotation(NotJson.class).contentType();
                    }
                }
                methodMap.put(method.getName(),
                    new RestMethod(method, reqParam, responseName, contentType, mt));
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

    protected boolean handleRequest (HttpServletRequest req, HttpServletResponse rsp,
            Map<String, RestMethod> methodMap) throws IOException {
        try {
            String methodName = getMethodName(req);
            RestMethod method = methodMap.get(methodName);
            String pathInfo = req.getPathInfo();
            _pathInfo.set(pathInfo == null ? "" :
                req.getPathInfo().substring(methodName.length() + 1));
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
                    serializeResponse(method, response, rsp.getWriter());
                    usedWriter = true;
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
        } finally {
            _reset.get().emit();
        }
        return true;
    }


    protected String stringParam (String name) {
        return _params.get().get(name);
    }

    protected int intParam (String name, int defValue) {
        return _params.get().get(name, Converters.TO_INT, defValue);
    }

    protected <T extends Enum<T>> T enumParam (String name, final Class<T> cls, T defValue) {
        return enumParam(name, cls, defValue, true);
    }

    protected <T extends Enum<T>> T enumParam (String name, final Class<T> cls,
            T defValue, final boolean upperCase) {
        return _params.get().get(name, new Function<String, T>() {
            public T apply (String input) {
                if (input == null) return null;
                try {
                    if (upperCase) input = input.toUpperCase();
                    return Enum.valueOf(cls, input);
                } catch (Exception e) {
                    return null;
                }
            }
        }, defValue);
    }

    protected String strId () {
        String pathInfo = _pathInfo.get();
        if (StringUtil.isBlank(pathInfo)) return null;
        int idx = pathInfo.indexOf('/');
        if (idx >= 0) {
            log.warning("Path info more complex than expected", "path", pathInfo);
        }
        return idx < 0 ? pathInfo : pathInfo.substring(0, idx);
    }

    protected int intId () {
        return toInt(strId(), -1);
    }

    protected int toInt (String value, int defval) {
        if (value == null) return defval;
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            log.warning("Received non-integer value", "value", value);
        }
        return defval;
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

    protected boolean methodIsMicrotome (Method method) {
        return false;
    }

    protected void serializeResponse (RestMethod method, Object response, PrintWriter out) {
        if (response == null) {
            response = new JsonObject();
            // default to an empty json object instead of "null", friendlier for the
            // client
        }
        if (method.responseName != null) {
            if (!(response instanceof JsonElement)) {
                response = _gson.toJsonTree(response);
            }
            JsonObject json = new JsonObject();
            json.add(method.responseName, (JsonElement)response);
            response = json;
        }
        if (response instanceof JsonElement) {
            _gson.toJson((JsonElement)response, out);
        } else {
            _gson.toJson(response, out);
        }
    }

    protected static class RestMethod {
        public final Method method;
        public final Class<?> requestClass;
        public final String responseName;
        public final String contentType;
        public final boolean microtome;

        public RestMethod (Method method, Class<?> requestClass, String responseName,
            String contentType, boolean microtome) {
            this.method = method;
            this.requestClass = requestClass;
            this.responseName = responseName;
            this.contentType = contentType;
            this.microtome = microtome;
        }
    }

    protected static final String DEFAULT_FIND_ALL = "_findAll";
    protected static final String DEFAULT_FIND = "_find";

    private static final Logger log = Logger.getLogger(SimplerServlet.class);

    protected String _baseEndpoint;
    protected Gson _gson;

    protected final ThreadLocal<UnitSignal> _reset;

    protected final RequestLocal<HttpServletRequest> _req;
    protected final RequestLocal<HttpServletResponse> _rsp;
    protected final RequestLocal<Parameters> _params;
    protected final RequestLocal<String> _pathInfo;

    protected final Map<String, RestMethod> _gets = new HashMap<String, RestMethod>();
    protected final Map<String, RestMethod> _posts = new HashMap<String, RestMethod>();
    protected final Map<String, RestMethod> _deletes = new HashMap<String, RestMethod>();
}
