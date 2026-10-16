package dev.forge.runtime;
final class Key { static { System.loadLibrary("c++_shared"); } static native byte[] read(); }
