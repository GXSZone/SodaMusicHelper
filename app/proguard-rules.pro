# 默认不开启混淆；此文件仅用于满足 release 构建引用，保持空规则。
# 若后续开启 minify，需要保留 Assists 反射相关类与 Kotlin 元数据。
-keep class com.ven.assists.** { *; }
