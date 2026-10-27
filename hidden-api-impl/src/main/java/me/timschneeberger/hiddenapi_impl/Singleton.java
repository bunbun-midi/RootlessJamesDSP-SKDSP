package me.timschneeberger.hiddenapi_impl;

public abstract class Singleton<T> {

    private T mInstance;

    protected abstract T create();

    public final T get() {
        synchronized (this) {
            if (mInstance == null) {
                try {
                    mInstance = create();
                }
                catch (Throwable ex) {
                    // Also catches LinkageError (e.g. NoSuchMethodError) when a hidden framework
                    // member is not accessible to this app
                    ex.printStackTrace();
                }
            }
            return mInstance;
        }
    }

    public final T getOrThrow() {
        T instance = get();
        if(instance == null)
            throw new NullPointerException();
        return instance;
    }
}