package org.roguenet.simpler;

/** An enumeration of the HTTP request methods currently supported by Simpler */
public enum RequestMethod {
    GET("GET"), POST("POST"), PUT("PUT"), DELETE("DELETE");

    public String getHttpName () { return _httpName; }

    RequestMethod (String httpName) {
        _httpName = httpName;
    }

    protected String _httpName;
}
