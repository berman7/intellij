/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.sync.importer;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.idea.blaze.android.projectview.GeneratedAndroidResourcesSection;
import com.google.idea.blaze.android.projectview.GenfilesPath;
import com.google.idea.blaze.android.sync.BlazeAndroidJavaSyncAugmenter;
import com.google.idea.blaze.android.sync.BlazeAndroidLibrarySource;
import com.google.idea.blaze.android.sync.model.AarLibrary;
import com.google.idea.blaze.android.sync.model.AndroidResourceModule;
import com.google.idea.blaze.android.sync.model.BlazeAndroidImportResult;
import com.google.idea.blaze.android.sync.model.BlazeResourceLibrary;
import com.google.idea.blaze.base.BlazeTestCase;
import com.google.idea.blaze.base.async.executor.BlazeExecutor;
import com.google.idea.blaze.base.async.executor.MockBlazeExecutor;
import com.google.idea.blaze.base.bazel.BazelBuildSystemProvider;
import com.google.idea.blaze.base.bazel.BuildSystemProvider;
import com.google.idea.blaze.base.ideinfo.AndroidAarIdeInfo;
import com.google.idea.blaze.base.ideinfo.AndroidIdeInfo;
import com.google.idea.blaze.base.ideinfo.ArtifactLocation;
import com.google.idea.blaze.base.ideinfo.JavaIdeInfo;
import com.google.idea.blaze.base.ideinfo.LibraryArtifact;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.ideinfo.TargetMap;
import com.google.idea.blaze.base.ideinfo.TargetMapBuilder;
import com.google.idea.blaze.base.io.FileOperationProvider;
import com.google.idea.blaze.base.model.primitives.Kind;
import com.google.idea.blaze.base.model.primitives.Kind.Provider;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.LanguageClass;
import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.model.primitives.WorkspaceType;
import com.google.idea.blaze.base.prefetch.MockPrefetchService;
import com.google.idea.blaze.base.prefetch.PrefetchService;
import com.google.idea.blaze.base.projectview.ProjectView;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.projectview.section.ListSection;
import com.google.idea.blaze.base.projectview.section.sections.DirectoryEntry;
import com.google.idea.blaze.base.projectview.section.sections.DirectorySection;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.ErrorCollector;
import com.google.idea.blaze.base.scope.output.IssueOutput;
import com.google.idea.blaze.base.settings.Blaze;
import com.google.idea.blaze.base.settings.BlazeImportSettings;
import com.google.idea.blaze.base.settings.BlazeImportSettingsManager;
import com.google.idea.blaze.base.settings.BuildSystem;
import com.google.idea.blaze.base.sync.projectview.ImportRoots;
import com.google.idea.blaze.base.sync.projectview.WorkspaceLanguageSettings;
import com.google.idea.blaze.base.sync.workspace.ArtifactLocationDecoder;
import com.google.idea.blaze.base.sync.workspace.WorkingSet;
import com.google.idea.blaze.java.AndroidBlazeRules;
import com.google.idea.blaze.java.JavaBlazeRules;
import com.google.idea.blaze.java.sync.BlazeJavaSyncAugmenter;
import com.google.idea.blaze.java.sync.importer.BlazeJavaWorkspaceImporter;
import com.google.idea.blaze.java.sync.importer.JavaSourceFilter;
import com.google.idea.blaze.java.sync.jdeps.MockJdepsMap;
import com.google.idea.blaze.java.sync.model.BlazeJarLibrary;
import com.google.idea.blaze.java.sync.model.BlazeJavaImportResult;
import com.google.idea.blaze.java.sync.source.JavaLikeLanguage;
import com.google.idea.blaze.java.sync.source.JavaSourcePackageReader;
import com.google.idea.blaze.java.sync.source.PackageManifestReader;
import com.google.idea.blaze.java.sync.source.SourceArtifact;
import com.google.idea.blaze.java.sync.workingset.JavaWorkingSet;
import com.google.idea.common.experiments.ExperimentService;
import com.google.idea.common.experiments.MockExperimentService;
import com.intellij.openapi.extensions.impl.ExtensionPointImpl;
import java.io.File;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for BlazeAndroidWorkspaceImporter */
@RunWith(JUnit4.class)
public class BlazeAndroidWorkspaceImporterTest extends BlazeTestCase {

  private final WorkspaceRoot workspaceRoot = new WorkspaceRoot(new File("/root"));

  private static final String FAKE_GEN_ROOT_EXECUTION_PATH_FRAGMENT =
      "bazel-out/gcc-4.X.Y-crosstool-v17-hybrid-grtev3-k8-fastbuild/bin";

  private static final ArtifactLocationDecoder FAKE_ARTIFACT_DECODER =
      (ArtifactLocationDecoder)
          artifactLocation -> new File("/", artifactLocation.getRelativePath());

  private static final BlazeImportSettings DUMMY_IMPORT_SETTINGS =
      new BlazeImportSettings("", "", "", "", BuildSystem.Bazel);

  private BlazeContext context;
  private ErrorCollector errorCollector = new ErrorCollector();
  private final MockJdepsMap jdepsMap = new MockJdepsMap();
  private final JavaWorkingSet workingSet =
      new JavaWorkingSet(
          workspaceRoot,
          new WorkingSet(ImmutableList.of(), ImmutableList.of(), ImmutableList.of()),
          Predicate.isEqual("BUILD"));
  private final WorkspaceLanguageSettings workspaceLanguageSettings =
      new WorkspaceLanguageSettings(
          WorkspaceType.ANDROID, ImmutableSet.of(LanguageClass.ANDROID, LanguageClass.JAVA));

  @Override
  protected void initTest(Container applicationServices, Container projectServices) {
    MockExperimentService mockExperimentService = new MockExperimentService();
    applicationServices.register(ExperimentService.class, mockExperimentService);

    BlazeExecutor blazeExecutor = new MockBlazeExecutor();
    applicationServices.register(BlazeExecutor.class, blazeExecutor);

    projectServices.register(BlazeImportSettingsManager.class, new BlazeImportSettingsManager());
    BlazeImportSettingsManager.getInstance(getProject()).setImportSettings(DUMMY_IMPORT_SETTINGS);

    MockFileOperationProvider mockFileOperationProvider = new MockFileOperationProvider();
    applicationServices.register(FileOperationProvider.class, mockFileOperationProvider);

    ExtensionPointImpl<Provider> targetKindEp =
        registerExtensionPoint(Kind.Provider.EP_NAME, Kind.Provider.class);
    targetKindEp.registerExtension(new AndroidBlazeRules());
    targetKindEp.registerExtension(new JavaBlazeRules());
    applicationServices.register(Kind.ApplicationState.class, new Kind.ApplicationState());

    context = new BlazeContext();
    context.addOutputSink(IssueOutput.class, errorCollector);

    registerExtensionPoint(BlazeJavaSyncAugmenter.EP_NAME, BlazeJavaSyncAugmenter.class);

    // For importJavaWorkspace.
    applicationServices.register(
        JavaSourcePackageReader.class,
        new JavaSourcePackageReader() {
          @Nullable
          @Override
          public String getDeclaredPackageOfJavaFile(
              BlazeContext context,
              ArtifactLocationDecoder artifactLocationDecoder,
              SourceArtifact sourceArtifact) {
            return null;
          }
        });

    applicationServices.register(PackageManifestReader.class, new PackageManifestReader());
    applicationServices.register(PrefetchService.class, new MockPrefetchService());

    registerExtensionPoint(JavaLikeLanguage.EP_NAME, JavaLikeLanguage.class)
        .registerExtension(new JavaLikeLanguage.Java());

    registerExtensionPoint(BuildSystemProvider.EP_NAME, BuildSystemProvider.class)
        .registerExtension(new BazelBuildSystemProvider());
  }

  private BlazeAndroidImportResult importWorkspace(
      WorkspaceRoot workspaceRoot, TargetMapBuilder targetMapBuilder, ProjectView projectView) {

    BuildSystem buildSystem = Blaze.getBuildSystem(project);
    ProjectViewSet projectViewSet = ProjectViewSet.builder().add(projectView).build();
    TargetMap targetMap = targetMapBuilder.build();
    JavaSourceFilter sourceFilter =
        new JavaSourceFilter(buildSystem, workspaceRoot, projectViewSet, targetMap);
    BlazeAndroidWorkspaceImporter workspaceImporter =
        new BlazeAndroidWorkspaceImporter(
            project,
            context,
            workspaceRoot,
            projectViewSet,
            targetMap,
            sourceFilter,
            FAKE_ARTIFACT_DECODER);

    return workspaceImporter.importWorkspace();
  }

  private BlazeJavaImportResult importJavaWorkspace(
      WorkspaceRoot workspaceRoot, TargetMapBuilder targetMapBuilder, ProjectView projectView) {

    BuildSystem buildSystem = Blaze.getBuildSystem(project);
    ProjectViewSet projectViewSet = ProjectViewSet.builder().add(projectView).build();
    TargetMap targetMap = targetMapBuilder.build();
    JavaSourceFilter sourceFilter =
        new JavaSourceFilter(buildSystem, workspaceRoot, projectViewSet, targetMap);
    BlazeJavaWorkspaceImporter blazeWorkspaceImporter =
        new BlazeJavaWorkspaceImporter(
            project,
            workspaceRoot,
            projectViewSet,
            workspaceLanguageSettings,
            targetMap,
            sourceFilter,
            jdepsMap,
            workingSet,
            FAKE_ARTIFACT_DECODER);

    return blazeWorkspaceImporter.importWorkspace(context);
  }

  /** Test that a two packages use the same un-imported android_library */
  @Test
  public void testResourceInheritance() {
    ProjectView projectView =
        ProjectView.builder()
            .add(
                ListSection.builder(DirectorySection.KEY)
                    .add(DirectoryEntry.include(new WorkspacePath("java/apps/example")))
                    .add(DirectoryEntry.include(new WorkspacePath("javatests/apps/example"))))
            .build();

    /** Deps are project -> lib0 -> lib1 -> shared project -> shared */
    TargetMapBuilder targetMapBuilder =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/apps/example/lib0:lib0")
                    .setKind("android_library")
                    .setBuildFile(source("java/apps/example/lib0/BUILD"))
                    .addSource(source("java/apps/example/lib0/SharedActivity.java"))
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/apps/example/lib0/AndroidManifest.xml"))
                            .addResource(source("java/apps/example/lib0/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.apps.example.lib0"))
                    .addDependency("//java/apps/example/lib1:lib1")
                    .setJavaInfo(
                        JavaIdeInfo.builder()
                            .addJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(gen("java/apps/example/lib0/lib0.jar"))
                                    .setClassJar(gen("java/apps/example/lib0/lib0.jar")))))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/apps/example/lib1:lib1")
                    .setKind("android_library")
                    .setBuildFile(source("java/apps/example/lib1/BUILD"))
                    .addSource(source("java/apps/example/lib1/SharedActivity.java"))
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/apps/example/lib1/AndroidManifest.xml"))
                            .addResource(source("java/apps/example/lib1/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.apps.example.lib1"))
                    .addDependency("//java/libraries/shared:shared")
                    .setJavaInfo(
                        JavaIdeInfo.builder()
                            .addJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(gen("java/apps/example/lib1/lib1.jar"))
                                    .setClassJar(gen("java/apps/example/lib1/lib1.jar")))))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/apps/example:example_debug")
                    .setKind("android_binary")
                    .setBuildFile(source("java/apps/example/BUILD"))
                    .addSource(source("java/apps/example/MainActivity.java"))
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/apps/example/AndroidManifest.xml"))
                            .addResource(source("java/apps/example/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.apps.example"))
                    .addDependency("//java/apps/example/lib0:lib0")
                    .addDependency("//java/libraries/shared:shared")
                    .setJavaInfo(
                        JavaIdeInfo.builder()
                            .addJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(gen("java/apps/example/example_debug.jar"))
                                    .setClassJar(gen("java/apps/example/example_debug.jar")))))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/libraries/shared:shared")
                    .setBuildFile(source("java/libraries/shared/BUILD"))
                    .setKind("android_library")
                    .addSource(source("java/libraries/shared/SharedActivity.java"))
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/libraries/shared/AndroidManifest.xml"))
                            .addResource(source("java/libraries/shared/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.libraries.shared"))
                    .setBuildFile(source("java/libraries/shared/BUILD"))
                    .setJavaInfo(
                        JavaIdeInfo.builder()
                            .addJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(gen("java/libraries/shared.jar"))
                                    .setClassJar(gen("java/libraries/shared.jar")))));

    BlazeAndroidImportResult result = importWorkspace(workspaceRoot, targetMapBuilder, projectView);
    errorCollector.assertNoIssues();

    assertThat(result.androidResourceModules)
        .containsExactly(
            AndroidResourceModule.builder(
                    TargetKey.forPlainTarget(Label.create("//java/apps/example:example_debug")))
                .addResourceAndTransitiveResource(source("java/apps/example/res"))
                .addTransitiveResource(source("java/apps/example/lib0/res"))
                .addTransitiveResource(source("java/apps/example/lib1/res"))
                .addTransitiveResource(source("java/libraries/shared/res"))
                .addTransitiveResourceDependency("//java/apps/example/lib0:lib0")
                .addTransitiveResourceDependency("//java/apps/example/lib1:lib1")
                .addTransitiveResourceDependency("//java/libraries/shared:shared")
                .build(),
            AndroidResourceModule.builder(
                    TargetKey.forPlainTarget(Label.create("//java/apps/example/lib0:lib0")))
                .addResourceAndTransitiveResource(source("java/apps/example/lib0/res"))
                .addTransitiveResource(source("java/apps/example/lib1/res"))
                .addTransitiveResource(source("java/libraries/shared/res"))
                .addTransitiveResourceDependency("//java/apps/example/lib1:lib1")
                .addTransitiveResourceDependency("//java/libraries/shared:shared")
                .build(),
            AndroidResourceModule.builder(
                    TargetKey.forPlainTarget(Label.create("//java/apps/example/lib1:lib1")))
                .addResourceAndTransitiveResource(source("java/apps/example/lib1/res"))
                .addTransitiveResource(source("java/libraries/shared/res"))
                .addTransitiveResourceDependency("//java/libraries/shared:shared")
                .build());
  }

  /** Test adding empty resource modules as jars. */
  @Test
  public void testEmptyResourceModuleIsAddedAsJar() {
    ProjectView projectView =
        ProjectView.builder()
            .add(
                ListSection.builder(DirectorySection.KEY)
                    .add(DirectoryEntry.include(new WorkspacePath("java/apps/example")))
                    .add(DirectoryEntry.include(new WorkspacePath("javatests/apps/example"))))
            .build();

    /** Deps are project -> lib0 (no res) -> lib1 (has res) \ -> lib2 (has res) */
    TargetMapBuilder response =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/apps/example/lib0:lib0")
                    .setKind("android_library")
                    .setBuildFile(source("java/apps/example/lib0/BUILD"))
                    .addSource(source("java/apps/example/lib0/SharedActivity.java"))
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/apps/example/lib0/AndroidManifest.xml"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.apps.example.lib0")
                            .setResourceJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(
                                        gen("java/apps/example/lib0/lib0_resources.jar"))
                                    .setClassJar(gen("java/apps/example/lib0/lib0_resources.jar"))))
                    .addDependency("//java/apps/example/lib1:lib1")
                    .addDependency("//java/apps/example/lib2:lib2")
                    .setJavaInfo(
                        JavaIdeInfo.builder()
                            .addJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(gen("java/apps/example/lib0/lib0.jar"))
                                    .setClassJar(gen("java/apps/example/lib0/lib0.jar")))
                            .addJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(
                                        gen("java/apps/example/lib0/lib0_resources.jar"))
                                    .setClassJar(
                                        gen("java/apps/example/lib0/lib0_resources.jar")))))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/apps/example/lib1:lib1")
                    .setKind("android_library")
                    .setBuildFile(source("java/apps/example/lib1/BUILD"))
                    .addSource(source("java/apps/example/lib1/SharedActivity.java"))
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/apps/example/lib1/AndroidManifest.xml"))
                            .addResource(source("java/apps/example/lib1/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.apps.example.lib1")
                            .setResourceJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(
                                        gen("java/apps/example/lib1/li11_resources.jar"))
                                    .setClassJar(gen("java/apps/example/lib1/lib1_resources.jar"))))
                    .setJavaInfo(
                        JavaIdeInfo.builder()
                            .addJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(gen("java/apps/example/lib1/lib1.jar"))
                                    .setClassJar(gen("java/apps/example/lib1/lib1.jar")))
                            .addJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(
                                        gen("java/apps/example/lib1/lib1_resources.jar"))
                                    .setClassJar(
                                        gen("java/apps/example/lib1/lib1_resources.jar")))))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/apps/example/lib2:lib2")
                    .setBuildFile(source("java/apps/example/lib2/BUILD"))
                    .setKind("android_library")
                    .addSource(source("java/apps/example/lib2/SharedActivity.java"))
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/apps/example/lib2/AndroidManifest.xml"))
                            .addResource(source("java/apps/example/lib2/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.libraries.example.lib2")
                            .setResourceJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(
                                        gen("java/apps/example/lib2/lib2_resources.jar"))
                                    .setClassJar(gen("java/apps/example/lib2/lib2_resources.jar"))))
                    .setBuildFile(source("java/apps/example/lib2/BUILD"))
                    .setJavaInfo(
                        JavaIdeInfo.builder()
                            .addJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(gen("java/apps/example/lib2/lib2.jar"))
                                    .setClassJar(gen("java/apps/example/lib2/lib2.jar")))
                            .addJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(
                                        gen("java/apps/example/lib2/lib2_resources.jar"))
                                    .setClassJar(
                                        gen("java/apps/example/lib2/lib2_resources.jar")))))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/apps/example:example_debug")
                    .setKind("android_binary")
                    .setBuildFile(source("java/apps/example/BUILD"))
                    .addSource(source("java/apps/example/MainActivity.java"))
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/apps/example/AndroidManifest.xml"))
                            .addResource(source("java/apps/example/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.apps.example"))
                    .addDependency("//java/apps/example/lib0:lib0")
                    .setJavaInfo(
                        JavaIdeInfo.builder()
                            .addJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(gen("java/apps/example/example_debug.jar"))
                                    .setClassJar(gen("java/apps/example/example_debug.jar")))));

    TargetMap targetMap = response.build();
    BlazeAndroidJavaSyncAugmenter syncAugmenter = new BlazeAndroidJavaSyncAugmenter();
    List<BlazeJarLibrary> jars = Lists.newArrayList();
    List<BlazeJarLibrary> genJars = Lists.newArrayList();
    ImportRoots importRoots =
        ImportRoots.builder(workspaceRoot, BuildSystem.Blaze)
            .add(ProjectViewSet.builder().add(projectView).build())
            .build();
    ProjectViewSet projectViewSet = ProjectViewSet.builder().add(projectView).build();
    for (TargetIdeInfo target : targetMap.targets()) {
      if (importRoots.importAsSource(target.getKey().getLabel())) {
        syncAugmenter.addJarsForSourceTarget(
            workspaceLanguageSettings, projectViewSet, target, jars, genJars);
      }
    }

    assertThat(
            jars.stream()
                .map(library -> library.libraryArtifact)
                .map(LibraryArtifact::getInterfaceJar)
                .map(artifactLocation -> new File(artifactLocation.getRelativePath()).getName())
                .collect(Collectors.toList()))
        .containsExactly("lib0_resources.jar");
  }

  @Test
  public void testIdlClassJarIsAddedAsLibrary() {
    ProjectView projectView =
        ProjectView.builder()
            .add(
                ListSection.builder(DirectorySection.KEY)
                    .add(DirectoryEntry.include(new WorkspacePath("example"))))
            .build();

    TargetMapBuilder targetMapBuilder =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//example:lib")
                    .setBuildFile(source("example/BUILD"))
                    .setKind("android_binary")
                    .addSource(source("example/MainActivity.java"))
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setResourceJavaPackage("example")
                            .setIdlJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(gen("example/libidl.jar"))
                                    .addSourceJar(gen("example/libidl.srcjar"))
                                    .build())
                            .setHasIdlSources(true)));

    TargetMap targetMap = targetMapBuilder.build();
    BlazeAndroidJavaSyncAugmenter syncAugmenter = new BlazeAndroidJavaSyncAugmenter();
    List<BlazeJarLibrary> jars = Lists.newArrayList();
    List<BlazeJarLibrary> genJars = Lists.newArrayList();
    ImportRoots importRoots =
        ImportRoots.builder(workspaceRoot, BuildSystem.Blaze)
            .add(ProjectViewSet.builder().add(projectView).build())
            .build();
    ProjectViewSet projectViewSet = ProjectViewSet.builder().add(projectView).build();
    for (TargetIdeInfo target : targetMap.targets()) {
      if (importRoots.importAsSource(target.getKey().getLabel())) {
        syncAugmenter.addJarsForSourceTarget(
            workspaceLanguageSettings, projectViewSet, target, jars, genJars);
      }
    }
    assertThat(
            genJars.stream()
                .map(library -> library.libraryArtifact)
                .map(LibraryArtifact::getInterfaceJar)
                .map(artifactLocation -> new File(artifactLocation.getRelativePath()).getName())
                .collect(Collectors.toList()))
        .containsExactly("libidl.jar");
  }

  @Test
  public void testAndroidResourceImport() {
    ProjectView projectView =
        ProjectView.builder()
            .add(
                ListSection.builder(DirectorySection.KEY)
                    .add(DirectoryEntry.include(new WorkspacePath("java/example"))))
            .build();

    TargetMapBuilder targetMapBuilder =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example:lib")
                    .setBuildFile(source("java/example/BUILD"))
                    .setKind("android_library")
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setLegacyResources(Label.create("//java/example:resources"))
                            .setManifestFile(source("java/example/AndroidManifest.xml"))
                            .addResource(source("java/example/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.example"))
                    .build())
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example:resources")
                    .setBuildFile(source("java/example/BUILD"))
                    .setKind("android_resources")
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/example/AndroidManifest.xml"))
                            .addResource(source("java/example/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.example"))
                    .build());

    BlazeAndroidImportResult result = importWorkspace(workspaceRoot, targetMapBuilder, projectView);
    errorCollector.assertNoIssues();
    assertThat(result.androidResourceModules)
        .containsExactly(
            AndroidResourceModule.builder(
                    TargetKey.forPlainTarget(Label.create("//java/example:resources")))
                .addResourceAndTransitiveResource(source("java/example/res"))
                .build());
  }

  @Test
  public void testResourceImportOutsideSourceFilterIsAddedToResourceLibrary() {
    ProjectView projectView =
        ProjectView.builder()
            .add(
                ListSection.builder(DirectorySection.KEY)
                    .add(DirectoryEntry.include(new WorkspacePath("java/example"))))
            .build();

    TargetMapBuilder targetMapBuilder =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example:lib")
                    .setBuildFile(source("java/example/BUILD"))
                    .setKind("android_library")
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/example/AndroidManifest.xml"))
                            .addResource(source("java/example/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.example"))
                    .addDependency("//java/example2:resources")
                    .build())
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example2:resources")
                    .setBuildFile(source("java/example2/BUILD"))
                    .setKind("android_library")
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/example2/AndroidManifest.xml"))
                            .addResource(source("java/example2/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.example2"))
                    .build());

    BlazeAndroidImportResult result = importWorkspace(workspaceRoot, targetMapBuilder, projectView);
    errorCollector.assertNoIssues();
    BlazeResourceLibrary library = result.resourceLibrary;
    assertThat(library).isNotNull();
    assertThat(library.sources)
        .containsExactly(
            ArtifactLocation.builder()
                .setRelativePath("java/example2/res")
                .setIsSource(true)
                .build());
  }

  @Test
  public void testConflictingResourceRClasses() {
    ProjectView projectView =
        ProjectView.builder()
            .add(
                ListSection.builder(DirectorySection.KEY)
                    .add(DirectoryEntry.include(new WorkspacePath("java/example"))))
            .build();

    TargetMapBuilder targetMapBuilder =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example:lib")
                    .setBuildFile(source("java/example/BUILD"))
                    .setKind("android_library")
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/example/AndroidManifest.xml"))
                            .addResource(source("java/example/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.example"))
                    .addDependency("//java/example2:resources")
                    .build())
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example:lib2")
                    .setBuildFile(source("java/example2/BUILD"))
                    .setKind("android_library")
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/example2/AndroidManifest.xml"))
                            .addResource(source("java/example/res2"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.example"))
                    .build());

    BlazeAndroidImportResult result = importWorkspace(workspaceRoot, targetMapBuilder, projectView);
    errorCollector.assertIssueContaining("Multiple R classes generated");

    assertThat(result.androidResourceModules)
        .containsExactly(
            AndroidResourceModule.builder(
                    TargetKey.forPlainTarget(Label.create("//java/example:lib")))
                .addResourceAndTransitiveResource(source("java/example/res"))
                .build());
  }

  @Test
  public void testMixingGeneratedAndNonGeneratedSourcesGeneratesIssue() {
    ProjectView projectView =
        ProjectView.builder()
            .add(
                ListSection.builder(DirectorySection.KEY)
                    .add(DirectoryEntry.include(new WorkspacePath("java/example"))))
            .build();

    TargetMapBuilder targetMapBuilder =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example:lib")
                    .setBuildFile(source("java/example/BUILD"))
                    .setKind("android_library")
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/example/AndroidManifest.xml"))
                            .addResource(source("java/example/res"))
                            .addResource(gen("java/example/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.example"))
                    .build());

    importWorkspace(workspaceRoot, targetMapBuilder, projectView);
    errorCollector.assertIssueContaining("Dropping 1 generated resource");
  }

  @Test
  public void testMixingGeneratedAndNonGeneratedSourcesWhitelisted() {
    ProjectView projectView =
        ProjectView.builder()
            .add(
                ListSection.builder(DirectorySection.KEY)
                    .add(DirectoryEntry.include(new WorkspacePath("java/example"))))
            .add(
                ListSection.builder(GeneratedAndroidResourcesSection.KEY)
                    .add(new GenfilesPath("java/example/res")))
            .build();

    TargetMapBuilder targetMapBuilder =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example:lib")
                    .setBuildFile(source("java/example/BUILD"))
                    .setKind("android_library")
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/example/AndroidManifest.xml"))
                            .addResource(source("java/example/res"))
                            .addResource(gen("java/example/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.example"))
                    .build());

    BlazeAndroidImportResult result = importWorkspace(workspaceRoot, targetMapBuilder, projectView);
    errorCollector.assertNoIssues();
    assertThat(result.androidResourceModules)
        .containsExactly(
            AndroidResourceModule.builder(
                    TargetKey.forPlainTarget(Label.create("//java/example:lib")))
                .addResourceAndTransitiveResource(source("java/example/res"))
                .addResourceAndTransitiveResource(gen("java/example/res"))
                .build());
  }

  @Test
  public void testMixingGeneratedAndNonGeneratedSourcesPartlyWhitelisted() {
    ProjectView projectView =
        ProjectView.builder()
            .add(
                ListSection.builder(DirectorySection.KEY)
                    .add(DirectoryEntry.include(new WorkspacePath("java/example")))
                    .add(DirectoryEntry.include(new WorkspacePath("java/example2")))
                    .add(DirectoryEntry.include(new WorkspacePath("java/uninterestingdir"))))
            .add(
                ListSection.builder(GeneratedAndroidResourcesSection.KEY)
                    .add(new GenfilesPath("java/example/res"))
                    .add(new GenfilesPath("unused/whitelisted/path/res")))
            .build();

    TargetMapBuilder targetMapBuilder =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example:lib")
                    .setBuildFile(source("java/example/BUILD"))
                    .setKind("android_library")
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/example/AndroidManifest.xml"))
                            .addResource(source("java/example/res"))
                            .addResource(gen("java/example/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.example"))
                    .build())
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example2:lib")
                    .setBuildFile(source("java/example2/BUILD"))
                    .setKind("android_library")
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/example2/AndroidManifest.xml"))
                            .addResource(source("java/example2/res"))
                            .addResource(gen("java/example2/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.example2"))
                    .build())
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/uninterestingdir:lib")
                    .setBuildFile(source("java/uninterestingdir/BUILD"))
                    .setKind("android_library")
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/uninterestingdir/AndroidManifest.xml"))
                            .addResource(source("java/uninterestingdir/res"))
                            .addResource(gen("java/uninterestingdir/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.uninterestingdir"))
                    .build());

    importWorkspace(workspaceRoot, targetMapBuilder, projectView);
    errorCollector.assertIssues(
        "Dropping 1 generated resource directories.\n"
            + "R classes will not contain resources from these directories.\n"
            + "Double-click to add to project view if needed to resolve references.",
        "Dropping generated resource directory "
            + String.format("'%s/java/example2/res'", FAKE_GEN_ROOT_EXECUTION_PATH_FRAGMENT)
            + " w/ 2 subdirs",
        "1 unused entries in project view section \"generated_android_resource_directories\":\n"
            + "unused/whitelisted/path/res");
  }

  @Test
  public void testMixingGeneratedAndNonGeneratedSourcesNoInterestingDirectories() {
    ProjectView projectView =
        ProjectView.builder()
            .add(
                ListSection.builder(DirectorySection.KEY)
                    .add(DirectoryEntry.include(new WorkspacePath("java/uninterestingdir"))))
            .build();

    TargetMapBuilder targetMapBuilder =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/uninterestingdir:lib")
                    .setBuildFile(source("java/uninterestingdir/BUILD"))
                    .setKind("android_library")
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/uninterestingdir/AndroidManifest.xml"))
                            .addResource(source("java/uninterestingdir/res"))
                            .addResource(gen("java/uninterestingdir/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.uninterestingdir"))
                    .build());

    BlazeAndroidImportResult result = importWorkspace(workspaceRoot, targetMapBuilder, projectView);
    errorCollector.assertNoIssues();
    assertThat(result.androidResourceModules)
        .containsExactly(
            AndroidResourceModule.builder(
                    TargetKey.forPlainTarget(Label.create("//java/uninterestingdir:lib")))
                .addResourceAndTransitiveResource(source("java/uninterestingdir/res"))
                .build());
  }

  @Test
  public void testAarImport_outsideSources_createsAarLibrary() {
    ProjectView projectView =
        ProjectView.builder()
            .add(
                ListSection.builder(DirectorySection.KEY)
                    .add(DirectoryEntry.include(new WorkspacePath("java/example"))))
            .build();

    TargetMapBuilder targetMapBuilder =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example:lib")
                    .setBuildFile(source("java/example/BUILD"))
                    .setKind(AndroidBlazeRules.RuleTypes.ANDROID_LIBRARY.getKind())
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/example/AndroidManifest.xml"))
                            .addResource(source("java/example/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("example"))
                    .setJavaInfo(JavaIdeInfo.builder())
                    .addSource(source("java/example/Source.java"))
                    .addDependency("//third_party/lib:an_aar")
                    .build())
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//third_party/lib:an_aar")
                    .setBuildFile(source("third_party/lib/BUILD"))
                    .setKind(AndroidBlazeRules.RuleTypes.AAR_IMPORT.getKind())
                    .setAndroidAarInfo(new AndroidAarIdeInfo(source("third_party/lib/lib_aar.aar")))
                    .setJavaInfo(
                        JavaIdeInfo.builder()
                            .addJar(
                                LibraryArtifact.builder()
                                    .setClassJar(
                                        gen(
                                            "third_party/lib/_aar/an_aar/"
                                                + "classes_and_libs_merged.jar"))))
                    .build());
    jdepsMap.put(
        TargetKey.forPlainTarget(Label.create("//java/example:lib")),
        ImmutableList.of(jdepsPath("third_party/lib/_aar/an_aar/classes_and_libs_merged.jar")));
    BlazeJavaImportResult javaResult =
        importJavaWorkspace(workspaceRoot, targetMapBuilder, projectView);
    BlazeAndroidImportResult androidResult =
        importWorkspace(workspaceRoot, targetMapBuilder, projectView);

    errorCollector.assertNoIssues();

    // We get 2 libraries representing the AAR. One from java and one from android.
    assertThat(javaResult.libraries).hasSize(1);
    assertThat(androidResult.aarLibraries).hasSize(1);
    assertThat(
            androidResult.aarLibraries.stream()
                .map(BlazeAndroidWorkspaceImporterTest::aarJarName)
                .collect(Collectors.toList()))
        .containsExactly("classes_and_libs_merged.jar");
    assertThat(
            androidResult.aarLibraries.stream()
                .map(BlazeAndroidWorkspaceImporterTest::aarName)
                .collect(Collectors.toList()))
        .containsExactly("lib_aar.aar");
    // Check that BlazeAndroidLibrarySource can filter out the java one, so that only the
    // android version takes effect.
    BlazeAndroidLibrarySource.AarJarFilter aarFilter =
        new BlazeAndroidLibrarySource.AarJarFilter(androidResult.aarLibraries);
    assertThat(aarFilter.test(javaResult.libraries.values().asList().get(0))).isFalse();
  }

  @Test
  public void testAarImport_outsideSourcesAndNoJdeps_keepsAarLibrary() {
    ProjectView projectView =
        ProjectView.builder()
            .add(
                ListSection.builder(DirectorySection.KEY)
                    .add(DirectoryEntry.include(new WorkspacePath("java/example"))))
            .build();

    TargetMapBuilder targetMapBuilder =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example:lib")
                    .setBuildFile(source("java/example/BUILD"))
                    .setKind(AndroidBlazeRules.RuleTypes.ANDROID_LIBRARY.getKind())
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/example/AndroidManifest.xml"))
                            .addResource(source("java/example/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("example"))
                    .setJavaInfo(JavaIdeInfo.builder())
                    .addSource(source("java/example/Source.java"))
                    .addDependency("//third_party/lib:an_aar")
                    .build())
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//third_party/lib:an_aar")
                    .setBuildFile(source("third_party/lib/BUILD"))
                    .setKind(AndroidBlazeRules.RuleTypes.AAR_IMPORT.getKind())
                    .setAndroidAarInfo(new AndroidAarIdeInfo(source("third_party/lib/lib_aar.aar")))
                    .setJavaInfo(
                        JavaIdeInfo.builder()
                            .addJar(
                                LibraryArtifact.builder()
                                    .setClassJar(
                                        gen(
                                            "third_party/lib/_aar/an_aar/"
                                                + "classes_and_libs_merged.jar"))))
                    .build());
    BlazeJavaImportResult javaResult =
        importJavaWorkspace(workspaceRoot, targetMapBuilder, projectView);
    BlazeAndroidImportResult androidResult =
        importWorkspace(workspaceRoot, targetMapBuilder, projectView);

    errorCollector.assertNoIssues();

    // The java importer performs jdeps optimization, but the android one does not.
    assertThat(javaResult.libraries).isEmpty();
    assertThat(
            androidResult.aarLibraries.stream()
                .map(BlazeAndroidWorkspaceImporterTest::aarName)
                .collect(Collectors.toList()))
        .containsExactly("lib_aar.aar");
  }

  @Test
  public void testAarImport_inSources_createsAarLibrary() {
    ProjectView projectView =
        ProjectView.builder()
            .add(
                ListSection.builder(DirectorySection.KEY)
                    .add(DirectoryEntry.include(new WorkspacePath("java/example"))))
            .build();

    TargetMapBuilder targetMapBuilder =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example:lib")
                    .setBuildFile(source("java/example/BUILD"))
                    .setKind(AndroidBlazeRules.RuleTypes.ANDROID_LIBRARY.getKind())
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/example/AndroidManifest.xml"))
                            .addResource(source("java/example/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.example"))
                    .setJavaInfo(JavaIdeInfo.builder())
                    .addSource(source("java/example/Source.java"))
                    .addDependency("//java/example:an_aar")
                    .build())
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example:an_aar")
                    .setBuildFile(source("java/example/BUILD"))
                    .setKind(AndroidBlazeRules.RuleTypes.AAR_IMPORT.getKind())
                    .setAndroidAarInfo(new AndroidAarIdeInfo(source("java/example/an_aar.aar")))
                    .setJavaInfo(
                        JavaIdeInfo.builder()
                            .addJar(
                                LibraryArtifact.builder()
                                    .setClassJar(
                                        gen(
                                            "java/example/_aar/an_aar/"
                                                + "classes_and_libs_merged.jar"))))
                    .build());
    jdepsMap.put(
        TargetKey.forPlainTarget(Label.create("//java/example:lib")),
        ImmutableList.of(jdepsPath("java/example/_aar/an_aar/classes_and_libs_merged.jar")));

    BlazeJavaImportResult javaResult =
        importJavaWorkspace(workspaceRoot, targetMapBuilder, projectView);
    BlazeAndroidImportResult androidResult =
        importWorkspace(workspaceRoot, targetMapBuilder, projectView);

    errorCollector.assertNoIssues();

    assertThat(
            androidResult.aarLibraries.stream()
                .map(BlazeAndroidWorkspaceImporterTest::aarJarName)
                .collect(Collectors.toList()))
        .containsExactly("classes_and_libs_merged.jar");
    assertThat(
            androidResult.aarLibraries.stream()
                .map(BlazeAndroidWorkspaceImporterTest::aarName)
                .collect(Collectors.toList()))
        .containsExactly("an_aar.aar");
    assertThat(javaResult.libraries).hasSize(1);
    BlazeAndroidLibrarySource.AarJarFilter aarFilter =
        new BlazeAndroidLibrarySource.AarJarFilter(androidResult.aarLibraries);
    assertThat(aarFilter.test(javaResult.libraries.values().asList().get(0))).isFalse();
  }

  @Test
  public void testAarImport_inSourcesAndNoJdeps_keepsAarLibrary() {
    ProjectView projectView =
        ProjectView.builder()
            .add(
                ListSection.builder(DirectorySection.KEY)
                    .add(DirectoryEntry.include(new WorkspacePath("java/example"))))
            .build();

    TargetMapBuilder targetMapBuilder =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example:lib")
                    .setBuildFile(source("java/example/BUILD"))
                    .setKind(AndroidBlazeRules.RuleTypes.ANDROID_LIBRARY.getKind())
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/example/AndroidManifest.xml"))
                            .addResource(source("java/example/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.google.android.example"))
                    .setJavaInfo(JavaIdeInfo.builder())
                    .addSource(source("java/example/Source.java"))
                    .addDependency("//java/example:an_aar")
                    .build())
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example:an_aar")
                    .setBuildFile(source("java/example/BUILD"))
                    .setKind(AndroidBlazeRules.RuleTypes.AAR_IMPORT.getKind())
                    .setAndroidAarInfo(new AndroidAarIdeInfo(source("java/example/an_aar.aar")))
                    .setJavaInfo(
                        JavaIdeInfo.builder()
                            .addJar(
                                LibraryArtifact.builder()
                                    .setClassJar(
                                        gen(
                                            "java/example/_aar/an_aar/"
                                                + "classes_and_libs_merged.jar"))))
                    .build());

    BlazeJavaImportResult javaResult =
        importJavaWorkspace(workspaceRoot, targetMapBuilder, projectView);
    BlazeAndroidImportResult androidResult =
        importWorkspace(workspaceRoot, targetMapBuilder, projectView);

    errorCollector.assertNoIssues();

    assertThat(javaResult.libraries).isEmpty();
    assertThat(
            androidResult.aarLibraries.stream()
                .map(BlazeAndroidWorkspaceImporterTest::aarName)
                .collect(Collectors.toList()))
        .containsExactly("an_aar.aar");
  }

  @Test
  public void testAarImport_multipleJarLibraries_aarLibraryOnlyOverridesAarJar() {
    ProjectView projectView =
        ProjectView.builder()
            .add(
                ListSection.builder(DirectorySection.KEY)
                    .add(DirectoryEntry.include(new WorkspacePath("java/example"))))
            .build();

    TargetMapBuilder targetMapBuilder =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//java/example:lib")
                    .setBuildFile(source("java/example/BUILD"))
                    .setKind(AndroidBlazeRules.RuleTypes.ANDROID_LIBRARY.getKind())
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("java/example/AndroidManifest.xml"))
                            .addResource(source("java/example/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("example"))
                    .setJavaInfo(JavaIdeInfo.builder())
                    .addSource(source("java/example/Source.java"))
                    .addDependency("//third_party/lib:consume_export_aar")
                    .addDependency("//third_party/lib:dep_library")
                    .build())
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//third_party/lib:consume_export_aar")
                    .setBuildFile(source("third_party/lib/BUILD"))
                    .setKind(AndroidBlazeRules.RuleTypes.AAR_IMPORT.getKind())
                    .setAndroidAarInfo(
                        new AndroidAarIdeInfo(source("third_party/lib/lib1_aar.aar")))
                    .setJavaInfo(
                        JavaIdeInfo.builder()
                            .addJar(
                                LibraryArtifact.builder()
                                    .setClassJar(
                                        gen(
                                            "third_party/lib/_aar/consume_export_aar/"
                                                + "classes_and_libs_merged.jar"))))
                    .addDependency("//third_party/lib:dep_aar")
                    .build())
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//third_party/lib:dep_aar")
                    .setBuildFile(source("third_party/lib/BUILD"))
                    .setKind(AndroidBlazeRules.RuleTypes.AAR_IMPORT.getKind())
                    .setAndroidAarInfo(
                        new AndroidAarIdeInfo(source("third_party/lib/lib2_aar.aar")))
                    .setJavaInfo(
                        JavaIdeInfo.builder()
                            .addJar(
                                LibraryArtifact.builder()
                                    .setClassJar(
                                        gen(
                                            "third_party/lib/_aar/dep_aar/"
                                                + "classes_and_libs_merged.jar"))))
                    .build())
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel("//third_party/lib:dep_library")
                    .setBuildFile(source("third_party/lib/BUILD"))
                    .setKind(AndroidBlazeRules.RuleTypes.ANDROID_LIBRARY.getKind())
                    .addSource(source("third_party/lib/SharedActivity.java"))
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setManifestFile(source("third_party/lib/AndroidManifest.xml"))
                            .addResource(source("third_party/lib/res"))
                            .setGenerateResourceClass(true)
                            .setResourceJavaPackage("com.lib"))
                    .setJavaInfo(
                        JavaIdeInfo.builder()
                            .addJar(
                                LibraryArtifact.builder()
                                    .setInterfaceJar(gen("third_party/lib/dep_library.jar"))
                                    .setClassJar(gen("third_party/lib/dep_library.jar"))))
                    .build());
    jdepsMap.put(
        TargetKey.forPlainTarget(Label.create("//java/example:lib")),
        ImmutableList.of(
            jdepsPath("third_party/lib/_aar/dep_aar/classes_and_libs_merged.jar"),
            jdepsPath("third_party/lib/_aar/consume_export_aar/classes_and_libs_merged.jar"),
            jdepsPath("third_party/lib/dep_library.jar")));
    BlazeJavaImportResult javaResult =
        importJavaWorkspace(workspaceRoot, targetMapBuilder, projectView);
    BlazeAndroidImportResult androidResult =
        importWorkspace(workspaceRoot, targetMapBuilder, projectView);

    errorCollector.assertNoIssues();

    assertThat(javaResult.libraries).hasSize(3);
    assertThat(androidResult.aarLibraries).hasSize(2);
    assertThat(
            androidResult.aarLibraries.stream()
                .map(BlazeAndroidWorkspaceImporterTest::aarJarName)
                .collect(Collectors.toList()))
        .containsExactly("classes_and_libs_merged.jar", "classes_and_libs_merged.jar");
    assertThat(
            androidResult.aarLibraries.stream()
                .map(BlazeAndroidWorkspaceImporterTest::aarName)
                .collect(Collectors.toList()))
        .containsExactly("lib1_aar.aar", "lib2_aar.aar");
    BlazeAndroidLibrarySource.AarJarFilter aarFilter =
        new BlazeAndroidLibrarySource.AarJarFilter(androidResult.aarLibraries);
    ImmutableList<BlazeJarLibrary> blazeJarLibraries = javaResult.libraries.values().asList();
    for (BlazeJarLibrary jarLibrary : blazeJarLibraries) {
      if (libraryJarName(jarLibrary).equals("dep_library.jar")) {
        assertThat(aarFilter.test(jarLibrary)).isTrue();
      } else {
        assertThat(aarFilter.test(jarLibrary)).isFalse();
      }
    }
  }

  /**
   * Mock provider to satisfy directory listing queries from {@link
   * com.google.idea.blaze.android.sync.importer.problems.GeneratedResourceClassifier}.
   */
  private static class MockFileOperationProvider extends FileOperationProvider {

    // Return a few non-translation directories so that directories are considered interesting,
    // or return only-translation directories so that it's considered uninteresting.
    @Override
    public File[] listFiles(File directory) {
      File interestingResDir1 = FAKE_ARTIFACT_DECODER.decode(gen("java/example/res"));
      if (directory.equals(interestingResDir1)) {
        return new File[] {
          new File("java/example/res/raw"), new File("java/example/res/values-es"),
        };
      }
      File interestingResDir2 = FAKE_ARTIFACT_DECODER.decode(gen("java/example2/res"));
      if (directory.equals(interestingResDir2)) {
        return new File[] {
          new File("java/example2/res/layout"), new File("java/example2/res/values-ar"),
        };
      }
      File uninterestingResDir = FAKE_ARTIFACT_DECODER.decode(gen("java/uninterestingdir/res"));
      if (directory.equals(uninterestingResDir)) {
        return new File[] {
          new File("java/uninterestingdir/res/values-ar"),
          new File("java/uninterestingdir/res/values-es"),
        };
      }
      return new File[0];
    }
  }

  private static String aarJarName(AarLibrary library) {
    return new File(library.libraryArtifact.jarForIntellijLibrary().getExecutionRootRelativePath())
        .getName();
  }

  private static String aarName(AarLibrary library) {
    return new File(library.aarArtifact.getExecutionRootRelativePath()).getName();
  }

  private static String libraryJarName(BlazeJarLibrary library) {
    return new File(library.libraryArtifact.jarForIntellijLibrary().getExecutionRootRelativePath())
        .getName();
  }

  private ArtifactLocation source(String relativePath) {
    return ArtifactLocation.builder().setRelativePath(relativePath).setIsSource(true).build();
  }

  private static ArtifactLocation gen(String relativePath) {
    return ArtifactLocation.builder()
        .setRootExecutionPathFragment(FAKE_GEN_ROOT_EXECUTION_PATH_FRAGMENT)
        .setRelativePath(relativePath)
        .setIsSource(false)
        .build();
  }

  private static String jdepsPath(String relativePath) {
    return FAKE_GEN_ROOT_EXECUTION_PATH_FRAGMENT + "/" + relativePath;
  }
}
