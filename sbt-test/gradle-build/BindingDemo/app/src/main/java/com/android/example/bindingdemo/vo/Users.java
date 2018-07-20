package com.android.example.bindingdemo.vo;

import com.android.example.bindingdemo.R;

public class Users {
    public static final int[] ICONS = {
            R.drawable.emo_im_angel,
            R.drawable.emo_im_cool,
            R.drawable.emo_im_crying,
            R.drawable.emo_im_embarrassed,
            R.drawable.emo_im_foot_in_mouth,
            R.drawable.emo_im_happy,
            R.drawable.emo_im_kissing,
            R.drawable.emo_im_laughing,
            R.drawable.emo_im_lips_are_sealed,
            R.drawable.emo_im_money_mouth,
            R.drawable.emo_im_sad,
            R.drawable.emo_im_surprised,
            R.drawable.emo_im_tongue_sticking_out,
            R.drawable.emo_im_undecided,
            R.drawable.emo_im_winking,
            R.drawable.emo_im_wtf,
            R.drawable.emo_im_yelling,
    };
    public static final User[] robots = new User[]{
            new User("romain", "guy", R.drawable.emo_im_yelling, User.ROBOT),
    };
    public static final User[] toolkities = new User[]{
            new User("chet", "haase", R.drawable.emo_im_angel, User.KITTEN),
            new User("adam", "powell", R.drawable.emo_im_cool, User.KITTEN),
            new User("alan", "viverette", R.drawable.emo_im_crying, User.KITTEN),
            new User("chris", "craik", R.drawable.emo_im_embarrassed, User.KITTEN),
            new User("george", "mount", R.drawable.emo_im_foot_in_mouth, User.KITTEN),
            new User("john", "reck", R.drawable.emo_im_happy, User.KITTEN),
            new User("Doris", "liu", R.drawable.emo_im_winking, User.KITTEN),
            new User("Teng-Hui", "Zhu", R.drawable.emo_im_laughing, User.KITTEN),
            new User("yigit", "boyar", R.drawable.emo_im_wtf, User.KITTEN),


    };
}
