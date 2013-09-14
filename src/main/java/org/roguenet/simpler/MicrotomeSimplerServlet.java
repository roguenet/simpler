package org.roguenet.simpler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microtome.Library;
import com.microtome.MicrotomeCtx;
import com.microtome.Page;
import com.microtome.core.LibraryItem;
import com.microtome.core.WritableObject;
import com.microtome.error.MicrotomeError;
import com.microtome.json.JsonUtil;
import com.samskivert.util.Logger;
import com.samskivert.util.StringUtil;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import react.UnitSignal;

/**
 * The microtome dependency in Simpler's POM is optional - make sure it is provided in a project's
 * POM to use MicrotomeSimplerServlet.
 */
public class MicrotomeSimplerServlet extends SimplerServlet {
    public MicrotomeSimplerServlet (String baseEndpoint, Gson gson,
            ThreadLocal<UnitSignal> reset, MicrotomeCtx microtome) {
        super(baseEndpoint, gson, reset);
        _microtome = microtome;
    }

    @Override protected boolean methodIsMicrotome (Method method) {
        return !method.isAnnotationPresent(NotMicrotome.class);
    }

    @Override protected void serializeResponse (RestMethod method, Object response,
            PrintWriter out) {
        if (!method.microtome || response == null) {
            super.serializeResponse(method, response, out);
            return;
        }
        if (!(response instanceof Page) && !(response instanceof Library)) {
            log.warning("Response must be a subclass of Page or Library, falling back on gson " +
                "serialization", "method", method.method);
            super.serializeResponse(method, response, out);
            return;
        }

        JsonObject json = new JsonObject();
        WritableObject writer = JsonUtil.createWriter(method.responseName, json);
        if (response instanceof Page) {
            _microtome.write((Page)response, writer);
            if (method.responseName != null) {
                JsonObject wrapper = new JsonObject();
                wrapper.add(method.responseName, json);
                json = wrapper;
            }
        } else {
            for (LibraryItem item : ((Library)response).children()) {
                _microtome.write(item, JsonUtil.createWriter(item.name(), json));
            }
        }
        _gson.toJson(json, out);
    }

    protected <T extends Page> T mtParam (String name) {
        String param = stringParam(name);
        if (StringUtil.isBlank(param)) return null;

        try {
            JsonObject obj = (JsonObject)new JsonParser().parse(param);
            Library lib = new Library();
            _microtome.load(lib, JsonUtil.createReaders(obj));
            @SuppressWarnings("unchecked")
            T page = (T)lib.getItem(name);
            return page;
        } catch (MicrotomeError me) {
            log.warning("Error reading Microtome parameter", "json", param, me);
            return null;
        }
    }

    protected final MicrotomeCtx _microtome;

    private static final Logger log = Logger.getLogger(MicrotomeSimplerServlet.class);
}
