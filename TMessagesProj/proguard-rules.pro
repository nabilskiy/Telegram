-keep public class com.google.android.gms.* { public *; }
-keepnames @com.google.android.gms.common.annotation.KeepName class *
-keepclassmembernames class * {
    @com.google.android.gms.common.annotation.KeepName *;
}
-keep class org.telegram.ormessenger.* { *; }
-keep class org.telegram.ormessenger.camera.* { *; }
-keep class org.telegram.ormessenger.secretmedia.* { *; }
-keep class org.telegram.ormessenger.support.* { *; }
-keep class org.telegram.ormessenger.support.* { *; }
-keep class org.telegram.ormessenger.time.* { *; }
-keep class org.telegram.ormessenger.video.* { *; }
-keep class org.telegram.ormessenger.voip.* { *; }
-keep class org.telegram.SQLite.** { *; }
-keep class org.telegram.tgnet.ConnectionsManager { *; }
-keep class org.telegram.tgnet.NativeByteBuffer { *; }
-keep class org.telegram.tgnet.RequestDelegateInternal { *; }
-keep class org.telegram.tgnet.RequestTimeDelegate { *; }
-keep class org.telegram.tgnet.RequestDelegate { *; }
-keep class org.telegram.tgnet.QuickAckDelegate { *; }
-keep class org.telegram.tgnet.WriteToSocketDelegate { *; }
-keep class com.google.android.exoplayer2.ext.** { *; }
-keep class com.google.android.exoplayer2.util.FlacStreamMetadata { *; }
-keep class com.google.android.exoplayer2.metadata.flac.PictureFrame { *; }
-keep class com.google.android.exoplayer2.decoder.SimpleOutputBuffer { *; }
# Use -keep to explicitly keep any other classes shrinking would remove
-dontoptimize
-dontobfuscate