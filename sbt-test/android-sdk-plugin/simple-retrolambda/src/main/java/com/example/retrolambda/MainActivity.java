package com.example.retrolambda;

import android.app.Activity;
import android.os.Bundle;

import com.google.common.collect.*;
import com.google.common.base.*;
import java.util.*;

public class MainActivity extends Activity
{
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
    }

    public static List<Integer> toIntList(List<Character> characters) {
        return Lists.transform(characters,  c -> (int) c.charValue());
    }
}
