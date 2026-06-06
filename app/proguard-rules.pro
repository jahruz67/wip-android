# OkHttp rules
-keepattributes Signature, InnerClasses, AnnotationDefault
-keepclassmembers class * extends okhttp3.ResponseBody { *; }
-keepclassmembers class * extends okhttp3.RequestBody { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Retrofit rules
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Gson rules
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }

# Prevent shrinking of serialized network request/response models
-keep class com.example.whisperflow.network.TranscriptionResponse { *; }
-keep class com.example.whisperflow.network.ChatRequest { *; }
-keep class com.example.whisperflow.network.ChatMessage { *; }
-keep class com.example.whisperflow.network.ChatResponse { *; }
-keep class com.example.whisperflow.network.ChatChoice { *; }

# AndroidX Security Crypto rules (Tink & KeyStore)
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
