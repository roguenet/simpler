simpler
=======

A (probably too) simple JSON REST library for Java.

Goals
=====

Simpler is a very simple REST library for Java, using JSON for message encoding and Servlets for 
HTTP transport. There are other, more sophisticated REST libraries for Java. If you don't need the
fancy bells and whistles, Simpler might be right for you.

Dependencies
============

Simpler uses GSON because I was already using it and didn't feel like switching to Jackson, which is
maybe better these days. There are no other explicit dependencies. Any Servlet container could be 
used.
