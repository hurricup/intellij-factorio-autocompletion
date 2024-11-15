import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.kt3k.gradle.plugin.coveralls.CoverallsTask
import java.text.SimpleDateFormat
import java.util.*

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("idea")
    id("jacoco")
    id("org.jetbrains.intellij.platform") version "2.0.1"
    id("com.github.kt3k.coveralls") version "2.12.2"
    id("org.sonarqube") version "5.1.0.4882"
    id("org.jetbrains.qodana") version "0.1.13"
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.changelog") version "1.3.1"
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://jitpack.io")
    }
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
    }
}

val isCI = environment("CI").map { it.toBoolean() }.orElse(false)
val withCoverage =
    environment("COVERALLS_REPO_TOKEN").orElse(properties("with_coverage")).map { !it.isEmpty() }.orElse(false)
val platformVersionProvider by extra(project.provider {
    properties("platformVersion").get() + properties("platformBranch").get() + properties("platformBuild").get()
})

val pluginProjectsNames = setOf("plugin")

fun getPluginChangelog(): String {
    val changelogHtml = StringBuilder()

    for (item in changelog.getAll().values) {
        changelogHtml.append("<h3>" + item.version + "</h3>\n")
        changelogHtml.append("<ul>")

        for (section in item.getSections()) {
            val changesType = section.key

            for (sectionItem in section.value) {
                changelogHtml.append("<li>[" + changesType + "] " + sectionItem.substring(2) + "</li>\n")
            }
        }

        changelogHtml.append("</ul>\n")
    }

    return changelogHtml.toString()
}

val isPlugin = project.name in pluginProjectsNames || true // this is for further usage of modularization
apply(plugin = if (isPlugin) "org.jetbrains.intellij.platform" else "org.jetbrains.intellij.platform.module")
apply(plugin = "com.github.kt3k.coveralls")
apply(plugin = "jacoco")
apply(plugin = "java")
apply(plugin = "org.jetbrains.kotlin.jvm")

version = properties("pluginVersion").get().ifEmpty { properties("platformVersion").get() } +
        properties("pluginBranch").get().ifEmpty { properties("platformBranch").get() } +
        properties("pluginBuild").get().ifEmpty { properties("platformBuild").get() }

changelog {
    path.set("${project.projectDir}/Changelog.md")
    version.set(project.version.toString())
    header.set("[${version.get()}] - ${SimpleDateFormat("dd.MM.yyyy").format(Date())}")
}

dependencies {
    implementation("org.jsoup:jsoup:1.15.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.9.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher") {
        because("Only needed to run tests in a version of IntelliJ IDEA that bundles older versions")
    }
    implementation("com.github.Marcono1234:gson-record-type-adapter-factory:v0.2.0")

    intellijPlatform {
        val pluginList = mutableListOf<String>("com.tang:${properties("emmyLuaVersion").get()}")
        val bundledPluginList =
            mutableListOf<String>() // "com.intellij.modules.json" it is extracted as a plugin in 243

        if (!isCI.get()) {
            pluginList.add("PsiViewer:${properties("psiViewerVersion").get()}")
        }

        instrumentationTools()
        testFramework(TestFrameworkType.JUnit5)
        jetbrainsRuntime()

        val runWith = properties("runWith").orElse("")
        val (ideType, ideVersion) = when (runWith.get()) {
            "CL" -> {
                "CL" to properties("clionVersion").get()
            }

            "PC" -> {
                "PC" to properties("pycharmVersion").get()
            }

            "PY" -> {
                "PY" to properties("pycharmVersion").get()
            }

            else -> {
                "IC" to platformVersionProvider.get()
            }
        }

        create(ideType, ideVersion, useInstaller = properties("useInstaller").get().toBoolean())

        plugins(pluginList)
        bundledPlugins(bundledPluginList)
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        sourceCompatibility = properties("javaVersion").get()
        targetCompatibility = properties("javaTargetVersion").get()
    }

    withType<KotlinCompile> {
        kotlinOptions.jvmTarget = properties("javaTargetVersion").get()
    }

    test {
        maxHeapSize = "2048m"
        outputs.upToDateWhen { false }

        systemProperty("junit.jupiter.execution.parallel.enabled", false)
        systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")

        if (project.hasProperty("overwrite")) {
            systemProperty("idea.tests.overwrite.data", "true")
        }

        if (project.hasProperty("youtrack.token")) {
            systemProperty("youtrack.token", properties("youtrack.token").get())
        }

        if (project.hasProperty("idea.split.test.logs")) {
            systemProperty("idea.split.test.logs", "true")
            systemProperty("idea.single.test.log.max.length", "100_000_000")
        }

        useJUnitPlatform {
            if (project.hasProperty("runtest")) {
                include("**/" + properties("runtest").get() + ".class")
            }
        }

        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = true
        }

        configure<JacocoTaskExtension> {
            isEnabled = withCoverage.get()
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }

        if (isCI.get()) {
            testLogging {
                events.addAll(
                    listOf(
                        TestLogEvent.PASSED,
                        TestLogEvent.SKIPPED,
                        TestLogEvent.FAILED,
                        TestLogEvent.STANDARD_OUT,
                        TestLogEvent.STANDARD_ERROR
                    )
                )
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
    }

    if (isPlugin) {
        publishPlugin {
            if (project.hasProperty("eap")) {
                channels.set(listOf("EAP"))
            }
            token.set(properties("jbToken").orElse(""))
        }

        patchPluginXml {
            version = project.version

            changeNotes.set(getPluginChangelog())

            pluginDescription.set(properties("descriptionFile").flatMap {
                providers.fileContents(layout.projectDirectory.file(it)).asText
            })
        }
    }

    val jacocoRootReport = register<JacocoReport>("jacocoRootReport") {
        group = "verification"
        description = "Generates an aggregate report from all projects"

        dependsOn(allprojects.map {
            it.tasks.named("jacocoTestReport").map { task -> task.dependsOn }
        })
        mustRunAfter(allprojects.map {
            it.tasks.named("jacocoTestReport").map { task -> task.mustRunAfter }
        })
        executionData(allprojects.map {
            it.tasks.named<JacocoReport>("jacocoTestReport").map { task -> task.executionData }
        })

        executionData(File("coverage").walkTopDown().filter { it.extension == "exec" }.toList())

        additionalSourceDirs.setFrom(allprojects.map {
            it.sourceSets.main.map { sourceSet -> sourceSet.allSource.srcDirs }
        })
        sourceDirectories.setFrom(allprojects.map {
            it.sourceSets.main.map { sourceSet -> sourceSet.allSource.srcDirs }
        })

        reports {
            html.required.set(true) // human readable
            xml.required.set(true) // required by coveralls
            csv.required.set(false)
        }
    }

    withType<CoverallsTask> {
        group = "verification"
        description = "Uploads the aggregated coverage report to Coveralls"
        dependsOn(jacocoRootReport)
    }

    register("generateLexers") { }

    runIde {
        project.properties.forEach { (key, value) ->
            if (key.startsWith("pass.")) {
                val passedKey = key.substring(5)
                println("Passing $passedKey => $value")
                systemProperty(passedKey, value.toString())
            }
        }

        jvmArgs("-Xmx2048m")
    }
}

val coverageReportFile = project.buildDir.resolve("reports/jacoco/jacocoRootReport/jacocoRootReport.xml")

