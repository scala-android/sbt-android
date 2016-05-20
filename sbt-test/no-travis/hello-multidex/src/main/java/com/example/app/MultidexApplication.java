package com.example.app;

import android.content.Context;
import android.support.multidex.MultiDex;
import android.app.Application;

public class MultidexApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }
}