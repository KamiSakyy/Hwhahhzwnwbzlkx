package com.tsuyu.messenger.data;

import com.tsuyu.messenger.Profile;
import com.tsuyu.messenger.net.TsuyuServerClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Profiles live ONLY on our own FastAPI server (messenger.db).
 * внешнего облака has been removed completely.
 */
public final class ProfileRepository {
    public interface Result<T> {
        void success(T value);
        default void error(String message) { }
    }

    public ProfileRepository() { }

    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
    }

    public static boolean validUsername(String username) {
        return username != null && username.matches("[a-z0-9_]{3,20}");
    }

    public void getProfile(String uid, Result<Profile> result) {
        TsuyuServerClient.getProfile(uid, new TsuyuServerClient.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject user) {
                if (user == null || user.length() == 0 || user.optString("username").isEmpty()) {
                    result.success(null);
                    return;
                }
                result.success(fromJson(user, uid));
            }
            @Override public void onError(String error) { result.success(null); }
        });
    }

    public static Profile fromJson(JSONObject user, String fallbackUid) {
        Profile p = new Profile();
        p.uid = user.optString("uid", user.optString("id", fallbackUid));
        p.username = user.optString("username");
        p.displayName = user.optString("display_name", user.optString("displayName"));
        p.about = user.optString("about");
        String b64 = user.optString("avatarBase64", "");
        p.avatarBase64 = b64 == null ? "" : b64; // бинарник аватара; путь avatar_url сюда не кладём
        return p;
    }

    public void saveProfile(String uid, String displayName, String username, String about,
                            String avatarBase64, Result<Profile> result) {
        String normalized = normalizeUsername(username);
        String name = displayName == null ? "" : displayName.trim();
        if (!validUsername(normalized)) {
            result.error("Юзернейм: 3–20 символов, латиница, цифры и _.");
            return;
        }
        if (name.isEmpty() || name.length() > 48) {
            result.error("Имя должно быть длиной от 1 до 48 символов.");
            return;
        }
        TsuyuServerClient.saveProfile(name, normalized, about == null ? "" : about.trim(),
                avatarBase64, new TsuyuServerClient.Callback<Void>() {
            @Override public void onSuccess(Void unused) {
                Profile profile = new Profile();
                profile.uid = uid;
                profile.username = normalized;
                profile.displayName = name;
                profile.about = about == null ? "" : about.trim();
                profile.avatarBase64 = avatarBase64 == null ? "" : avatarBase64;
                result.success(profile);
            }
            @Override public void onError(String error) {
                if (error != null && (error.contains("taken") || error.contains("409"))) {
                    result.error("Этот @юзернейм уже занят.");
                } else {
                    result.error("Не удалось сохранить профиль. Проверьте соединение с сервером.");
                }
            }
        });
    }

    public void searchUsers(String prefix, String ownUid, Result<List<Profile>> result) {
        String normalized = normalizeUsername(prefix);
        if (normalized.isEmpty()) {
            result.success(Collections.emptyList());
            return;
        }
        TsuyuServerClient.searchUsers(normalized, new TsuyuServerClient.Callback<JSONArray>() {
            @Override public void onSuccess(JSONArray array) {
                List<Profile> list = new ArrayList<>();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.optJSONObject(i);
                        if (obj == null) continue;
                        Profile p = fromJson(obj, obj.optString("uid", obj.optString("id")));
                        if (p.uid == null || p.uid.equals(ownUid)) continue;
                        list.add(p);
                    }
                }
                result.success(list);
            }
            @Override public void onError(String error) {
                result.error("Не удалось выполнить поиск. Проверьте соединение с сервером.");
            }
        });
    }
}
