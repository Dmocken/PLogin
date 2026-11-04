package com.pimeng.plogin.skin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.common.collect.HashMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.pimeng.plogin.PLogin;
import com.pimeng.plogin.config.Config;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.UUIDUtil;

/**
 * 离线玩家皮肤。
 *
 * 思路参考 SkinsRestorer + MCCustomSkinLoader：
 * 1. 如果名字是正版账号（Mojang API 能查到），直接取 Mojang 的 signed textures 属性；
 * 2. 否则取 LittleSkin / Blessing Skin 的 CustomSkinAPI（/<name>.json）皮肤 URL；
 * 3. 因为原版客户端对“其他玩家”的皮肤要求 signatureState == SIGNED
 *    （PlayerInfo: requireSecure = !isLocalPlayer），皮肤站自己的签名不是 Mojang key，
 *    所以再用 MineSkin 把皮肤 URL 签成 Mojang 可验证的 textures 属性。
 *
 * 如果 MineSkin 不可用，可以配置 offlineSkinAllowUnsignedFallback=true 下发未签名属性，
 * 但只有本机玩家自己可能看到，其他玩家会显示默认皮肤。
 */
public final class SkinService {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Cached> SIGNED_URL_CACHE = new ConcurrentHashMap<>();
    private static final Object MINESKIN_LOCK = new Object();
    private static final String USER_AGENT = "PLogin/1.0 (Minecraft hybrid auth)";
    private static long nextMineSkinAt;

    private SkinService() {
    }

    public record SkinData(String value, String signature, String source) {
    }

    private record Cached(SkinData data, long expiresAt) {
    }

    /**
     * 给离线 GameProfile 注入 textures 属性。必须在 LoginFinished 发送前调用。
     * UUIDUtil.createOfflineProfile 的 PropertyMap 是 ImmutableMultimap，
     * 不能直接 put，必须构造一个新的可变 PropertyMap + 新 GameProfile。
     */
    public static GameProfile applyOfflineSkin(GameProfile profile) {
        Config config = PLogin.config();
        if (config == null || !config.enabled || !config.offlineSkinsEnabled) {
            return profile;
        }
        String provider = config.offlineSkinProvider == null ? "auto" : config.offlineSkinProvider;
        if ("none".equals(provider)) {
            return profile;
        }

        SkinData data = resolve(profile.name(), config, provider);
        if (data == null || data.value() == null || data.value().isBlank()) {
            PLogin.LOGGER.info("[PLogin] 离线皮肤: {} 未找到，使用默认皮肤", profile.name());
            return profile;
        }
        try {
            Property property = data.signature() == null || data.signature().isBlank()
                    ? new Property("textures", data.value())
                    : new Property("textures", data.value(), data.signature());
            // PropertyMap 构造器内部会 ImmutableMultimap.copyOf，所以先建可变 Multimap，
            // 把所有属性放进去，最后再构造 PropertyMap。
            HashMultimap<String, Property> propertyMap = HashMultimap.create();
            for (Map.Entry<String, Property> entry : profile.properties().entries()) {
                propertyMap.put(entry.getKey(), entry.getValue());
            }
            propertyMap.put("textures", property);
            PLogin.LOGGER.info("[PLogin] 离线皮肤: {} <- {} ({}, textures={})", profile.name(), data.source(),
                    data.signature() == null || data.signature().isBlank() ? "unsigned" : "signed",
                    textureTypes(data.value()));
            return new GameProfile(profile.id(), profile.name(), new PropertyMap(propertyMap));
        } catch (Exception e) {
            PLogin.LOGGER.warn("[PLogin] 无法给 {} 应用离线皮肤", profile.name(), e);
            return profile;
        }
    }

    private static SkinData resolve(String name, Config config, String provider) {
        String key = name.toLowerCase(Locale.ROOT);
        Cached cached = CACHE.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) {
            return cached.data();
        }

        SkinData data = null;
        try {
            if (!"littleskin".equals(provider) && !"site".equals(provider)) {
                data = fetchMojang(name, config);
            }
            if (data == null && ("auto".equals(provider) || "littleskin".equals(provider) || "site".equals(provider))) {
                data = fetchSkinSite(name, config);
            }
        } catch (Exception e) {
            PLogin.LOGGER.warn("[PLogin] 查询 {} 的离线皮肤失败", name, e);
        }

        long ttlMinutes = data != null ? config.offlineSkinCacheMinutes : 5;
        CACHE.put(key, new Cached(data, now + ttlMinutes * 60_000L));
        return data;
    }

    /**
     * 正版名 -> Mojang UUID -> sessionserver profile (signed textures)。
     * 对于非正版名，api.mojang.com 返回 204/404，直接 null。
     */
    private static SkinData fetchMojang(String name, Config config) {
        int timeout = config.offlineSkinTimeoutSeconds;
        JsonObject lookup = getJson("https://api.mojang.com/users/profiles/minecraft/" + encode(name), timeout);
        if (lookup == null || !lookup.has("id")) {
            return null;
        }
        String id = lookup.get("id").getAsString();
        JsonObject profile = getJson(
                "https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false", timeout);
        if (profile == null || !profile.has("properties")) {
            return null;
        }
        JsonArray properties = profile.getAsJsonArray("properties");
        for (JsonElement element : properties) {
            JsonObject property = element.getAsJsonObject();
            if (!property.has("name") || !"textures".equals(property.get("name").getAsString())) {
                continue;
            }
            String value = string(property, "value");
            String signature = string(property, "signature");
            if (value != null) {
                return new SkinData(value, signature == null ? "" : signature, "mojang:" + name);
            }
        }
        return null;
    }

    /**
     * LittleSkin/Blessing Skin 的 CustomSkinAPI：GET <root>/<name>.json
     * 返回 {"skins":{"default":"<hash>"}} 或 {"skin":"<hash>"} 等。
     */
    private static SkinData fetchSkinSite(String name, Config config) {
        String root = normalizeRoot(config.offlineSkinSiteRoot);
        JsonObject json = getJson(root + encode(name) + ".json", config.offlineSkinTimeoutSeconds);
        if (json == null) {
            return null;
        }

        String hash = null;
        boolean slim = false;
        String capeHash = string(json, "cape");
        String elytraHash = string(json, "elytra");
        JsonObject skins = json.has("skins") && json.get("skins").isJsonObject()
                ? json.getAsJsonObject("skins") : null;
        if (skins != null) {
            if (capeHash == null) {
                capeHash = string(skins, "cape");
            }
            if (elytraHash == null) {
                elytraHash = string(skins, "elytra");
            }
            hash = string(skins, "slim");
            if (hash != null) {
                slim = true;
            }
            if (hash == null) {
                hash = string(skins, "default");
            }
            if (hash == null) {
                hash = string(skins, "classic");
            }
            if (hash == null) {
                hash = string(skins, "skin");
            }
            if (hash == null) {
                hash = firstSkinHash(skins);
            }
        }
        if (hash == null) {
            hash = string(json, "skin");
        }
        if (hash == null && json.has("textures") && json.get("textures").isJsonObject()) {
            JsonObject textures = json.getAsJsonObject("textures");
            if (capeHash == null) {
                capeHash = string(textures, "cape");
            }
            if (elytraHash == null) {
                elytraHash = string(textures, "elytra");
            }
            hash = string(textures, "slim");
            if (hash != null) {
                slim = true;
            }
            if (hash == null) {
                hash = string(textures, "default");
            }
            if (hash == null) {
                hash = string(textures, "skin");
            }
            if (hash == null) {
                hash = firstSkinHash(textures);
            }
        }
        if (hash == null) {
            return null;
        }

        String textureUrl = root + "textures/" + hash;
        if (config.offlineSkinMineSkinSigning) {
            SkinData signed = signWithMineSkin(textureUrl, slim, config);
            if (signed != null) {
                return signed;
            }
            if (!config.offlineSkinAllowUnsignedFallback) {
                return null;
            }
        }
        return unsignedSkin(name, textureUrl, slim,
                capeHash == null ? null : root + "textures/" + capeHash,
                elytraHash == null ? null : root + "textures/" + elytraHash);
    }

    /**
     * MineSkin 会用他们的正版账号池把图片换成 textures.minecraft.net 的图片，
     * 并返回 Mojang 可验证签名的 value + signature。这样原版客户端对其他玩家也认。
     */
    private static SkinData signWithMineSkin(String textureUrl, boolean slim, Config config) {
        long now = System.currentTimeMillis();
        Cached cached = SIGNED_URL_CACHE.get(textureUrl);
        if (cached != null && cached.expiresAt() > now) {
            return cached.data();
        }

        if (!waitMineSkin(config)) {
            PLogin.LOGGER.warn("[PLogin] MineSkin 队列等待过久，跳过 {} 的皮肤签名（避免登录超时）", textureUrl);
            return null;
        }
        try {
            JsonObject body = new JsonObject();
            body.addProperty("variant", slim ? "slim" : "classic");
            body.addProperty("visibility", "unlisted");
            body.addProperty("url", textureUrl);

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://api.mineskin.org/v2/generate"))
                    .timeout(Duration.ofSeconds(Math.min(15, Math.max(8, config.offlineSkinTimeoutSeconds))))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
            if (config.offlineSkinMineSkinApiKey != null && !config.offlineSkinMineSkinApiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + config.offlineSkinMineSkinApiKey.trim());
            }

            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                if (root.has("success") && root.get("success").getAsBoolean()) {
                    JsonObject data = root.getAsJsonObject("skin")
                            .getAsJsonObject("texture")
                            .getAsJsonObject("data");
                    String value = data.get("value").getAsString();
                    String signature = data.get("signature").getAsString();
                    SkinData result = new SkinData(value, signature, "mineskin:" + textureUrl);
                    SIGNED_URL_CACHE.put(textureUrl, new Cached(result,
                            now + config.offlineSkinCacheMinutes * 60_000L));
                    updateMineSkinRateLimit(root);
                    return result;
                }
            }
            PLogin.LOGGER.warn("[PLogin] MineSkin 签名失败: HTTP {} {}", response.statusCode(), snippet(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            PLogin.LOGGER.warn("[PLogin] MineSkin 请求异常: {}", e.toString());
        }
        return null;
    }

    private static SkinData unsignedSkin(String name, String textureUrl, boolean slim, String capeUrl, String elytraUrl) {
        JsonObject root = new JsonObject();
        root.addProperty("timestamp", System.currentTimeMillis());
        root.addProperty("profileId", UUIDUtil.createOfflinePlayerUUID(name).toString().replace("-", ""));
        root.addProperty("profileName", name);
        JsonObject textures = new JsonObject();
        JsonObject skin = new JsonObject();
        skin.addProperty("url", textureUrl);
        if (slim) {
            JsonObject metadata = new JsonObject();
            metadata.addProperty("model", "slim");
            skin.add("metadata", metadata);
        }
        textures.add("SKIN", skin);
        if (capeUrl != null) {
            JsonObject cape = new JsonObject();
            cape.addProperty("url", capeUrl);
            textures.add("CAPE", cape);
        }
        if (elytraUrl != null) {
            JsonObject elytra = new JsonObject();
            elytra.addProperty("url", elytraUrl);
            textures.add("ELYTRA", elytra);
        }
        root.add("textures", textures);
        String value = Base64.getEncoder().encodeToString(GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        return new SkinData(value, "", "site-unsigned:" + name);
    }

    private static String textureTypes(String value) {
        try {
            String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject textures = root.getAsJsonObject("textures");
            return textures == null ? "[]" : textures.keySet().toString();
        } catch (Exception e) {
            return "?";
        }
    }

    private static boolean waitMineSkin(Config config) {
        synchronized (MINESKIN_LOCK) {
            long now = System.currentTimeMillis();
            long wait = nextMineSkinAt - now;
            // 登录阶段原版最多 600 tick（30s）；这里最多等 6s，避免排队导致 slow_login 踢出。
            if (wait > 6000L) {
                return false;
            }
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            boolean hasKey = config.offlineSkinMineSkinApiKey != null && !config.offlineSkinMineSkinApiKey.isBlank();
            nextMineSkinAt = System.currentTimeMillis() + (hasKey ? 1000L : 6000L);
            return true;
        }
    }

    private static void updateMineSkinRateLimit(JsonObject root) {
        try {
            JsonObject rateLimit = root.getAsJsonObject("rateLimit");
            if (rateLimit == null) {
                return;
            }
            JsonObject next = rateLimit.getAsJsonObject("next");
            if (next != null && next.has("relative")) {
                long relative = next.get("relative").getAsLong();
                synchronized (MINESKIN_LOCK) {
                    nextMineSkinAt = Math.max(nextMineSkinAt, System.currentTimeMillis() + Math.max(relative, 0));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static JsonObject getJson(String url, int timeoutSeconds) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return JsonParser.parseString(response.body()).getAsJsonObject();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            PLogin.LOGGER.debug("[PLogin] 皮肤查询失败 {}: {}", url, e.toString());
        }
        return null;
    }

    private static String firstSkinHash(JsonObject object) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (key.contains("cape") || key.contains("elytra")) {
                continue;
            }
            if (!entry.getValue().isJsonNull()) {
                String value = entry.getValue().getAsString();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        String value = object.get(key).getAsString();
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeRoot(String root) {
        String value = root == null || root.isBlank() ? "https://littleskin.cn/csl/" : root.trim();
        return value.endsWith("/") ? value : value + "/";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}
