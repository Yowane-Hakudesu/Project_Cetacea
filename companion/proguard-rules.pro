# 伴侣端没有反射/序列化需求，保留默认规则即可
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**
