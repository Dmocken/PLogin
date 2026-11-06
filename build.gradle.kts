import java.io.File
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("fabric-loom") version "1.19.3"
}

// ---------------------------------------------------------------------------
// 1.21.x 构建：源码仍然只维护一份（面向 Mojmap 的 26.x 写法），
// 这里在编译前把 src/ 复制到 build/generated/ 并按目标版本打补丁：
//   - 1.21.9 / 1.21.10：Identifier -> ResourceLocation，权限改回 hasPermission(int)
// 之后交给 Loom：官方 mapping 反混淆服务端 jar + remapJar 把产物与 Mixin 注解一起 remap 到 intermediary。
// ---------------------------------------------------------------------------

// 默认值就是当前发布目标；换版本用 -P 覆盖，例如
//   .\gradlew build -Pmc_version=1.21.11 -Pfabric_api_version=0.141.6+1.21.11
val mcVersion: String = providers.gradleProperty("mc_version").orElse("1.21.10").get()
val modVersion: String = providers.gradleProperty("mod_version").orElse("1.1.1").get()
val loaderVersion: String = providers.gradleProperty("loader_version").orElse("0.20.1").get()
val fabricApiVersion: String = providers.gradleProperty("fabric_api_version")
    .orElse("0.138.4+1.21.10").get()

/** 1.21.11 才改名 Identifier、才有 Permissions/PermissionSet；1.21.9 与 1.21.10 仍是旧命名。 */
val legacyNaming: Boolean = mcVersion == "1.21.9" || mcVersion == "1.21.10"

// Loom 1.19 起不再默认启用 Mixin 注解处理器（也不再需要 refmap），
// mixin 里的 Mojmap 名字由 Loom 在 remapJar 阶段直接改写成 intermediary。
version = "v$modVersion"
group = "com.pimeng.plogin"

// 产物名与 26.x 保持一致：PLogin-<Minecraft 版本>-v<模组版本>.jar
base {
    archivesName.set("PLogin-$mcVersion")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

// ---------------------------------------------------------------- 源码补丁
fun patchJava(text: String): String {
    if (!legacyNaming) {
        return text
    }
    var patched = text
    // 类改名：import、字段类型、静态工厂一起换
    patched = patched.replace("net.minecraft.resources.Identifier", "net.minecraft.resources.ResourceLocation")
    patched = patched.replace(Regex("\\bIdentifier\\b"), "ResourceLocation")
    // 1.21.11 才引入 Permissions / CommandSourceStack#permissions()
    patched = patched.replace(Regex("(?m)^import net\\.minecraft\\.server\\.permissions\\.Permissions;\\r?\\n"), "")
    patched = patched.replace(
        "source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)",
        "source.hasPermission(3)"
    )
    return patched
}

val generatedSrcDir = layout.buildDirectory.dir("generated/plogin-src-$mcVersion")

val patchSources = tasks.register("patchSources") {
    description = "把 src/ 复制到 build/ 并按 Minecraft 版本打源码补丁"
    val srcRoot = layout.projectDirectory.dir("src")
    val output = generatedSrcDir
    inputs.dir(srcRoot).withPropertyName("src")
    inputs.property("mcVersion", mcVersion)
    inputs.property("modVersion", modVersion)
    outputs.dir(output).withPropertyName("generated")

    doLast {
        val outRoot = output.get().asFile
        val javaOut = File(outRoot, "java")
        val resOut = File(outRoot, "resources")
        val staged = File(outRoot, "staged")
        outRoot.deleteRecursively()
        srcRoot.asFile.copyRecursively(staged, overwrite = true)

        var patchedFiles = 0
        staged.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(staged).path
            if (file.extension == "java") {
                val target = File(javaOut, relative)
                target.parentFile.mkdirs()
                val original = file.readText()
                val patched = patchJava(original)
                if (patched != original) {
                    patchedFiles++
                }
                target.writeText(patched)
            } else {
                val target = File(resOut, relative)
                target.parentFile.mkdirs()
                file.copyTo(target, overwrite = true)
            }
        }
        staged.deleteRecursively()

        // fabric.mod.json 里的目标版本 / 模组版本 / 描述
        val modJson = File(resOut, "fabric.mod.json")
        modJson.writeText(
            modJson.readText()
                .replace("\"minecraft\": \"26.2\"", "\"minecraft\": \"$mcVersion\"")
                .replace("\"version\": \"1.1.1\"", "\"version\": \"$modVersion\"")
                .replace("Minecraft 26.2 servers", "Minecraft $mcVersion servers")
        )
        logger.lifecycle("[PLogin] $mcVersion 源码补丁命中 $patchedFiles 个文件（Identifier/权限 API）")
    }
}

sourceSets {
    named("main") {
        java.setSrcDirs(emptyList<String>())
        java.srcDir(generatedSrcDir.map { it.dir("java") })
        resources.setSrcDirs(emptyList<String>())
        resources.srcDir(generatedSrcDir.map { it.dir("resources") })
    }
}

tasks.named("compileJava") { dependsOn(patchSources) }
tasks.named("processResources") { dependsOn(patchSources) }
