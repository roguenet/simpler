package org.roguenet.simpler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.microtome.MicrotomeCtx;
import com.microtome.Page;
import com.microtome.core.WritableObject;
import com.microtome.json.JsonUtil;
import com.samskivert.util.Logger;
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
        if (!(response instanceof Page)) {
            log.warning("Response must be a subclass of Page, falling back on gson serialization",
                "method", method.method);
            super.serializeResponse(method, response, out);
            return;
        }

        JsonObject json = new JsonObject();
        WritableObject writer = JsonUtil.createWriter(method.responseName, json);
        _microtome.write((Page)response, writer);
        JsonObject wrapper = new JsonObject();
        wrapper.add(method.responseName, json);
        _gson.toJson(wrapper, out);
    }

    protected final MicrotomeCtx _microtome;

    private static final Logger log = Logger.getLogger(MicrotomeSimplerServlet.class);
}
