package org.roguenet.simpler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(value=RetentionPolicy.RUNTIME)
public @interface RestGet
{
    /**
     * If set, instead of serializing this object directly to the client, a wrapping Json object
     * is generated instead that has a single property of this name, with the method return value
     * as the value.
     */
    String name () default "";
}

