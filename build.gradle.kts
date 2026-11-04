// 工程级构建脚本：只声明插件版本，具体配置在各模块的 build.gradle.kts
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Room 的注解处理走 KSP；版本必须与 Kotlin 版本严格对应
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
