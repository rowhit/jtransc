/*
 * Copyright 2016 Carlos Ballesteros Velasco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jtransc.gen.haxe

import com.jtransc.JTranscVersion
import com.jtransc.annotation.haxe.*
import com.jtransc.ast.*
import com.jtransc.ast.feature.SwitchesFeature
import com.jtransc.error.InvalidOperationException
import com.jtransc.error.invalidOp
import com.jtransc.error.noImpl
import com.jtransc.gen.GenTarget
import com.jtransc.gen.GenTargetDescriptor
import com.jtransc.gen.GenTargetInfo
import com.jtransc.gen.GenTargetProcessor
import com.jtransc.io.ProcessResult2
import com.jtransc.io.ProcessUtils
import com.jtransc.log.log
import com.jtransc.template.Minitemplate
import com.jtransc.time.measureProcess
import com.jtransc.vfs.LocalVfs
import com.jtransc.vfs.SyncVfsFile
import com.jtransc.vfs.UserKey
import com.jtransc.vfs.getCached
import java.io.File
import java.lang.reflect.Proxy

object HaxeGenDescriptor : GenTargetDescriptor() {
	override val name = "haxe"
	override val longName = "Haxe"
	override val sourceExtension = "hx"
	override val outputExtension = "bin"
	override val extraLibraries = listOf<String>()
	override val extraClasses = listOf<String>()
	override fun getGenerator() = GenHaxe
}

//val HaxeFeatures = setOf(GotosFeature, SwitchesFeature)
val HaxeFeatures = setOf(SwitchesFeature)

private val HAXE_LIBS_KEY = UserKey<List<HaxeLib.LibraryRef>>()

fun AstProgram.haxeLibs(settings: AstBuildSettings): List<HaxeLib.LibraryRef> = this.getCached(HAXE_LIBS_KEY) {
	this.classes
		.map { it.annotations[HaxeAddLibraries::value] }
		.filterNotNull()
		.flatMap { it.toList() }
		.map { HaxeLib.LibraryRef.fromVersion(it) }
}

fun AstProgram.haxeExtraFlags(settings: AstBuildSettings): List<Pair<String, String>> {
	return this.haxeLibs(settings).map { "-lib" to it.nameWithVersion } + listOf(
		//"-dce" to "no"
		//"-D" to "analyzer-no-module",
		//"--no-inline" to "1",
		//"--no-opt" to "1"
	)
}

fun AstProgram.haxeExtraDefines(settings: AstBuildSettings): List<String> {
	//-D no-analyzer
	//--times : measure compilation times
	//--no-inline : disable inlining
	//--no-opt : disable code optimizations
	//const_propagation: Implements sparse conditional constant propagation to promote values that are known at compile-time to usage places. Also detects dead branches.
	//copy_propagation: Detects local variables that alias other local variables and replaces them accordingly.
	//local_dce: Detects and removes unused local variables.
	//fusion: Moves variable expressions to its usage in case of single-occurrence. Disabled on Flash and Java.
	//purity_inference: Infers if fields are "pure", i.e. do not have any side-effects. This can improve the effect of the fusion module.
	//unreachable_code: Reports unreachable code.

	return listOf(
		if (settings.analyzer) "analyzer" else "no-analyzer"
	)
}

fun AstProgram.haxeInstallRequiredLibs(settings: AstBuildSettings) {
	val libs = this.haxeLibs(settings)
	log(":: REFERENCED LIBS: $libs")
	for (lib in libs) {
		log(":: TRYING TO INSTALL LIBRARY $lib")
		HaxeLib.installIfNotExists(lib)
	}
}

fun GenTargetInfo.haxeCopyEmbeddedResourcesToFolder(assetsFolder: File?) {
	val program = this.program
	val files = program.classes.map { it.annotations[HaxeAddAssets::value] }.filterNotNull().flatMap { it.toList() }
	//val assetsFolder = settings.assets.firstOrNull()
	val resourcesVfs = program.resourcesVfs
	log("GenTargetInfo.haxeCopyResourcesToAssetsFolder: $assetsFolder")
	if (assetsFolder != null) {
		val outputVfs = LocalVfs(assetsFolder)
		for (file in files) {
			log("GenTargetInfo.haxeCopyResourcesToAssetsFolder.copy: $file")
			outputVfs[file] = resourcesVfs[file]
		}
	}
}

object HaxeGenTools {
	fun getSrcFolder(tempdir: String): SyncVfsFile {
		log("Temporal haxe files: $tempdir/jtransc-haxe")
		File("$tempdir/jtransc-haxe/src").mkdirs()
		return LocalVfs(File("$tempdir/jtransc-haxe")).ensuredir()["src"]
	}
}

val GenTargetInfo.mergedAssetsFolder: File get() = File("${this.targetDirectory}/merged-assets")

class HaxeTemplateString(val names: HaxeNames, val tinfo: GenTargetInfo, val settings: AstBuildSettings, val actualSubtarget: HaxeAddSubtarget) {
	val program = tinfo.program
	val outputFile2 = File(File(tinfo.outputFile).absolutePath)
	val tempAssetsDir = tinfo.mergedAssetsFolder
	val tempdir = tinfo.targetDirectory
	val srcFolder = HaxeGenTools.getSrcFolder(tempdir)

	val params = hashMapOf(
		"srcFolder" to srcFolder.realpathOS,
		"haxeExtraFlags" to program.haxeExtraFlags(settings),
		"haxeExtraDefines" to program.haxeExtraDefines(settings),
		"actualSubtarget" to actualSubtarget,
		"outputFile" to outputFile2.absolutePath,
		"release" to tinfo.settings.release,
		"debug" to !tinfo.settings.release,
		"settings" to settings,
		"title" to settings.title,
		"name" to settings.name,
		"package" to settings.package_,
		"version" to settings.version,
		"company" to settings.company,
		"initialWidth" to settings.initialWidth,
		"initialHeight" to settings.initialHeight,
		"orientation" to settings.orientation.lowName,
		"haxeExtraFlags" to program.haxeExtraFlags(settings),
		"haxeExtraDefines" to program.haxeExtraDefines(settings),
		"tempAssetsDir" to tempAssetsDir.absolutePath,
		"embedResources" to settings.embedResources,
		"assets" to settings.assets,
		"hasIcon" to !settings.icon.isNullOrEmpty(),
		"icon" to settings.icon,
		"libraries" to settings.libraries
	)

	init {
		params["defaultBuildCommand"] = {
			Minitemplate("""
				haxe
				-cp
				{{ srcFolder }}
				-main
				{{ entryPointFile }}
				{% if debug %}
					-debug
				{% end %}
				{{ actualSubtarget.cmdSwitch }}
				{{ outputFile }}
				{% for flag in haxeExtraFlags %}
					{{ flag.first }}
					{{ flag.second }}
				{% end %}
				{% for define in haxeExtraDefines %}
					-D
					define
				{% end %}
			""").invoke(params)
		}
	}

	fun setProgramInfo(info: GenHaxe.ProgramInfo) {
		params["entryPointFile"] = info.entryPointFile
		params["entryPointClass"] = names.getHaxeClassFqName(info.entryPointClass)
	}

	private fun evalReference(type: String, desc: String): String {
		val dataParts = desc.split(':')
		val clazz = names.program[dataParts[0].fqname]!!
		return when (type.toUpperCase()) {
			"SINIT" -> names.getHaxeClassFqName(clazz.name) + ".SI()"
			"CONSTRUCTOR" -> {
				"new ${names.getHaxeClassFqName(clazz.name)}().${names.getHaxeMethodName(AstMethodRef(clazz.name, "<init>", AstType.demangleMethod(dataParts[1])))}"
			}
			"SMETHOD", "METHOD" -> {
				val methodName = if (dataParts.size >= 3) {
					names.getHaxeMethodName(AstMethodRef(clazz.name, dataParts[1], AstType.demangleMethod(dataParts[2])))
				} else {
					val methods = clazz.methodsByName[dataParts[1]]!!
					if (methods.size > 1) invalidOp("Several signatures, please specify signature")
					names.getHaxeMethodName(methods.first())
				}
				if (type == "SMETHOD") names.getHaxeClassFqName(clazz.name) + "." + methodName else methodName
			}
			"SFIELD", "FIELD" -> {
				val fieldName = names.getHaxeFieldName(clazz.fieldsByName[dataParts[1]]!!)
				if (type == "SFIELD") names.getHaxeClassFqName(clazz.name) + "." + fieldName else fieldName
			}
			"CLASS" -> names.getHaxeClassFqName(clazz.name)
			else -> invalidOp("Unknown type!")
		}
	}

	class ProgramRefNode(val ts: HaxeTemplateString, val type:String, val desc:String) : Minitemplate.BlockNode {
		override fun eval(context: Minitemplate.Context) {
			context.write(ts.evalReference(type, desc))
		}
	}

	val miniConfig = Minitemplate.Config(
		extraTags = listOf(
			Minitemplate.Tag(
				":programref:", setOf(), null,
				aliases = listOf(
					//"sinit", "constructor", "smethod", "method", "sfield", "field", "class",
					"SINIT", "CONSTRUCTOR", "SMETHOD", "METHOD", "SFIELD", "FIELD", "CLASS"
				)
			) { ProgramRefNode(this, it.first().token.name, it.first().token.content) }
		)
	)

	fun gen(template: String): String = Minitemplate(template, miniConfig).invoke(params)
}

class HaxeGenTargetProcessor(val tinfo: GenTargetInfo, val settings: AstBuildSettings) : GenTargetProcessor {
	val actualSubtargetName = tinfo.subtarget
	val availableHaxeSubtargets = tinfo.program.allAnnotations
		.map { it.toObject<HaxeAddSubtargetList>() ?: it.toObject<HaxeAddSubtarget>() }
		.flatMap {
			if (it == null) {
				listOf()
			} else if (it is HaxeAddSubtargetList) {
				it.value.toList()
			} else if (it is HaxeAddSubtarget) {
				listOf(it)
			} else {
				listOf()
			}
		}
		.filterNotNull()
	val actualSubtarget: HaxeAddSubtarget = availableHaxeSubtargets.last { it.name == actualSubtargetName || actualSubtargetName in it.alias }

	val outputFile2 = File(File(tinfo.outputFile).absolutePath)
	//val tempdir = System.getProperty("java.io.tmpdir")
	val tempdir = tinfo.targetDirectory
	var info: GenHaxe.ProgramInfo? = null
	lateinit var gen: GenHaxeGen
	val program = tinfo.program
	val srcFolder = HaxeGenTools.getSrcFolder(tempdir)
	val tempAssetsDir = tinfo.mergedAssetsFolder
	val tempAssetsVfs = LocalVfs(tempAssetsDir)
	val names = HaxeNames(program, minimize = settings.minimizeNames)
	val haxeTemplateString = HaxeTemplateString(names, tinfo, settings, actualSubtarget)

	override fun buildSource() {
		gen = GenHaxeGen(
			tinfo = tinfo,
			program = program,
			features = AstFeatures(),
			srcFolder = srcFolder,
			featureSet = HaxeFeatures,
			settings = settings,
			names = names,
			haxeTemplateString = haxeTemplateString
		)
		info = gen._write()
		haxeTemplateString.setProgramInfo(info!!)
	}

	//val BUILD_COMMAND = listOf("haxelib", "run", "lime", "@@SWITCHES", "build", "@@SUBTARGET")


	override fun compile(): Boolean {
		val names = tinfo
		if (info == null) throw InvalidOperationException("Must call .buildSource first")
		val info = info!!
		outputFile2.delete()
		log("haxe.build (" + JTranscVersion.getVersion() + ") source path: " + srcFolder.realpathOS)

		program.haxeInstallRequiredLibs(settings)
		tinfo.haxeCopyEmbeddedResourcesToFolder(outputFile2.parentFile)

		log("Copying assets... ")
		for (asset in settings.assets) {
			LocalVfs(asset).copyTreeTo(tempAssetsVfs)
		}

		log("Compiling... ")

		val copyFilesBeforeBuildTemplate = program.classes.flatMap { it.annotations[HaxeAddFilesBeforeBuildTemplate::value]?.toList() ?: listOf() }
		for (file in copyFilesBeforeBuildTemplate) srcFolder[file] = haxeTemplateString.gen(program.resourcesVfs[file].readString())


		val cmd = haxeTemplateString.gen(
			program.allAnnotations[HaxeCustomBuildCommandLine::value]?.joinToString("\n") ?: "{{ defaultBuildCommand() }}"
		).split("\n").map { it.trim() }.filter { it.isNotEmpty() }

		log("Compiling: ${cmd.joinToString(" ")}")
		println("Compiling: ${cmd.joinToString(" ")}")
		return ProcessUtils.runAndRedirect(srcFolder.realfile, cmd).success
	}

	override fun run(redirect: Boolean): ProcessResult2 {
		if (!outputFile2.exists()) {
			return ProcessResult2(-1, "file $outputFile2 doesn't exist")
		}
		val fileSize = outputFile2.length()
		log("run: ${outputFile2.absolutePath} ($fileSize bytes)")
		val parentDir = outputFile2.parentFile

		val runner = actualSubtarget.interpreter
		val arguments = listOf(outputFile2.absolutePath + actualSubtarget.interpreterSuffix)

		println("Running: $runner ${arguments.joinToString(" ")}")
		return measureProcess("Running") {
			ProcessUtils.run(parentDir, runner, arguments, redirect = redirect)
		}
	}
}

object GenHaxe : GenTarget {
	//val copyFiles = HaxeCopyFiles
	//val mappings = HaxeMappings()

	override val runningAvailable: Boolean = true

	override fun getProcessor(tinfo: GenTargetInfo, settings: AstBuildSettings): GenTargetProcessor {
		return HaxeGenTargetProcessor(tinfo, settings)
	}

	data class ProgramInfo(val entryPointClass: FqName, val entryPointFile: String, val vfs: SyncVfsFile) {
		//fun getEntryPointFq(program: AstProgram) = getHaxeClassFqName(program, entryPointClass)
	}
}