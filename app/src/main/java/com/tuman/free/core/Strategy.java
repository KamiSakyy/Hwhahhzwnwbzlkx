package com.tuman.free.core;

/** One tpws launch profile. {@code args} are passed to tpws verbatim. */
public final class Strategy {

    public final String id;
    public final String name;
    public final String desc;
    public final String[] args;

    public Strategy(String id, String name, String desc, String... args) {
        this.id = id;
        this.name = name;
        this.desc = desc;
        this.args = args;
    }

    public String argsString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
