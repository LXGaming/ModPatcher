package me.nallar.modpatcher;

import javassist.ClassLoaderPool;
import me.nallar.javapatcher.patcher.Patcher;
import me.nallar.javapatcher.patcher.Patches;
import me.nallar.mixin.internal.MixinApplicator;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.*;
import java.nio.file.*;

class ModPatcherTransformer {
	public static final ClassLoaderPool pool;
	private static final String MOD_PATCHES_DIRECTORY = "./ModPatches/";
	private static final Patcher patcher;
	private static final String ALREADY_LOADED_PROPERTY_NAME = "nallar.ModPatcher.alreadyLoaded";
	private static final String DUMP_PROPERTY_NAME = "nallar.ModPatcher.dump";
	private static final boolean DUMP = !System.getProperty(DUMP_PROPERTY_NAME, "").isEmpty();
	private static boolean classLoaderInitialised = false;
	private static MixinApplicator mixinApplicator;

	static {
		PatcherLog.info("ModPatcher running under classloader " + ModPatcherTransformer.class.getClassLoader().getClass().getName());

		checkForMultipleClassLoads();

		try {
			patcher = new Patcher(pool = new ClassLoaderPool(), Patches.class, new MCPMappings());

			// TODO - issue #2. Determine layout/config file structure
			recursivelyAddXmlFiles(new File(MOD_PATCHES_DIRECTORY), patcher);
		} catch (Throwable t) {
			throw logError("Failed to create Patcher", t);
		}
	}

	private static Error logError(String message, Throwable t) {
		PatcherLog.error(message, t);
		return new Error(message, t);
	}

	private static void checkForMultipleClassLoads() {
		if (System.getProperty(ALREADY_LOADED_PROPERTY_NAME) != null) {
			Error e = logError("Detected multiple classloads of ModPatcher - classloading issue?", new Throwable());
			if (!System.getProperty(ALREADY_LOADED_PROPERTY_NAME).equals("breakEverything"))
				throw e;
		} else {
			System.setProperty(ALREADY_LOADED_PROPERTY_NAME, "true");
		}
	}

	static Patcher getPatcher() {
		return patcher;
	}

	@SuppressWarnings("deprecation")
	private static void recursivelyAddXmlFiles(File directory, Patcher patcher) {
		File[] files = directory.listFiles();
		if (files == null)
			return;

		try {
			for (File f : files) {
				if (f.isDirectory()) {
					recursivelyAddXmlFiles(f, patcher);
				} else if (f.getName().endsWith(".xml")) {
					patcher.readPatchesFromXmlInputStream(new FileInputStream(f));
				} else if (f.getName().endsWith(".json")) {
					patcher.readPatchesFromJsonInputStream(new FileInputStream(f));
				}
			}
		} catch (IOException e) {
			PatcherLog.warn("Failed to load patch", e);
		}
	}

	static IClassTransformer getInstance() {
		return ClassTransformer.INSTANCE;
	}

	static void initialiseClassLoader(LaunchClassLoader classLoader) {
		if (classLoaderInitialised)
			return;
		classLoaderInitialised = true;

		classLoader.addTransformerExclusion("me.nallar.whocalled.");
		classLoader.addTransformerExclusion("me.nallar.javatransformer.");
		classLoader.addTransformerExclusion("me.nallar.javapatcher.");
		classLoader.addTransformerExclusion("me.nallar.mixin.");
		classLoader.addTransformerExclusion("javassist.");
		classLoader.addTransformerExclusion("com.github.javaparser.");

		LaunchClassLoaderUtil.instance = classLoader;
		ModPatcherTweaker.add();
		LaunchClassLoaderUtil.addTransformer(ModPatcherTransformer.getInstance());
		LaunchClassLoaderUtil.dumpTransformersIfEnabled();
		LaunchClassLoaderUtil.removeRedundantExclusions();
	}

	static String getDefaultPatchesDirectory() {
		return MOD_PATCHES_DIRECTORY;
	}

	static MixinApplicator getMixinApplicator() {
		MixinApplicator mixinApplicator = ModPatcherTransformer.mixinApplicator;

		if (mixinApplicator == null) {
			ModPatcherTransformer.mixinApplicator = mixinApplicator = new MixinApplicator();
			mixinApplicator.setMakeAccessible(false);
			mixinApplicator.setNoMixinIsError(true);
			mixinApplicator.setLog(PatcherLog::info);
		}

		return mixinApplicator;
	}

	private static class ClassTransformer implements IClassTransformer {
		static IClassTransformer INSTANCE = new ClassTransformer();
		private boolean init;

		private static void dumpIfEnabled(String name, byte[] data) {
			if (!DUMP || !name.contains("net.minecraft"))
				return;

			Path path = Paths.get("./DUMP/" + name + ".class");
			try {
				Files.createDirectories(path.getParent());
				Files.write(path, data);
			} catch (IOException e) {
				PatcherLog.error("Failed to dump class " + name, e);
			}
		}

		@Override
		public byte[] transform(String name, String transformedName, byte[] bytes) {
			if (!init) {
				init = true;
				patcher.logDebugInfo();
			}

			dumpIfEnabled(transformedName + "_unpatched", bytes);

			final byte[] originalBytes = bytes;
			if (mixinApplicator != null) {
				bytes = getMixinApplicator().getMixinTransformer().transformClass(() -> originalBytes, name).get();
			}

			LaunchClassLoaderUtil.cacheSrgBytes(name, bytes);
			try {
				bytes = patcher.patch(name, bytes);
			} catch (Throwable t) {
				PatcherLog.error("Failed to patch " + name, t);
			} finally {
				LaunchClassLoaderUtil.releaseSrgBytes(name);
			}

			if (originalBytes != bytes)
				dumpIfEnabled(transformedName, bytes);

			return bytes;
		}
	}
}
