package org.roguenet.simpler.util;

import react.SignalView;
import react.UnitSlot;

/**
 * A ThreadLocal that receives a signal telling it to reset. The intention is for that signal to
 * be dispatched at the start of a new HTTP service call so that this ThreadLocal resets at the
 * beginning of each new request.
 */
public class RequestLocal<T> extends ThreadLocal<T> {
    public RequestLocal (ThreadLocal<? extends SignalView<?>> reset) {
        _reset = reset;
    }

    @Override public T get () {
        if (!_connected.get()) {
            _reset.get().connect(new UnitSlot() { @Override public void onEmit () { remove(); } });
            _connected.set(true);
        }

        return super.get();
    }

    protected final ThreadLocal<? extends SignalView<?>> _reset;
    protected final ThreadLocal<Boolean> _connected = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue () { return Boolean.FALSE; }
    };
}
