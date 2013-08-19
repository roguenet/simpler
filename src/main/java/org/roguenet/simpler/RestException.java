package org.roguenet.simpler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

public class RestException extends ServletException {
    public static final int DEFAULT_CODE = 200;
    public static final int INTERNAL_ERROR = 500;

    public final int code;

    public static void throwIf (boolean expr, String message) throws RestException {
        throwIf(expr, DEFAULT_CODE, message);
    }

    public static void throwIf (boolean expr, int code, String message) throws RestException {
        if (expr) throw new RestException(code, message);
    }

    public static void accessDenied (boolean redirectToLogin) throws RestException {
        throw new RestException(redirectToLogin ? 201 : 200, "Access denied.");
    }

    public RestException (String message) {
        this(DEFAULT_CODE, message);
    }

    public RestException (int code, String message) {
        super(message);
        this.code = code;
    }

    public void write (Gson gson, HttpServletResponse rsp) throws IOException {
        rsp.setHeader("Content-Type", "application/json");
        JsonObject json = new JsonObject();
        json.addProperty("message", getMessage());
        json.addProperty("code", code);
        JsonObject root = new JsonObject();
        root.add("error", json);
        gson.toJson(root, rsp.getWriter());
    }
}
