package com.novawallet.app;
import okhttp3.*; import java.io.IOException;
public class Http{ static final OkHttpClient C=new OkHttpClient(); static final MediaType JSON=MediaType.get("application/json");
 public static String get(String u)throws IOException{ try(Response r=C.newCall(new Request.Builder().url(u).header("User-Agent","NovaWallet").build()).execute()){ return r.body().string(); } }
 public static String post(String u,String json)throws IOException{ try(Response r=C.newCall(new Request.Builder().url(u).post(RequestBody.create(json,JSON)).build()).execute()){ return r.body().string(); } }
 public static String postText(String u,String txt)throws IOException{ try(Response r=C.newCall(new Request.Builder().url(u).post(RequestBody.create(txt,MediaType.get("text/plain"))).build()).execute()){ String s=r.body().string(); if(!r.isSuccessful())throw new IOException(s); return s; } }
}
