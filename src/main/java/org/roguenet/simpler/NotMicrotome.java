package org.roguenet.simpler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * By default, methods in a MicrotomeSimplerServlet will use Microtome to serialize their response.
 * If this annotation is present, a method will use Gson normally, as in SimplerServlet.
 */
@Target({ElementType.METHOD})
@Retention(value=RetentionPolicy.RUNTIME)
public @interface NotMicrotome
{
}

