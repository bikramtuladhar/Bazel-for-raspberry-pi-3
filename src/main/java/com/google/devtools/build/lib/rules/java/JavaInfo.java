// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.java;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMap;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapBuilder;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.packages.NativeProvider;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import javax.annotation.Nullable;

/** A Skylark declared provider that encapsulates all providers that are needed by Java rules. */
@SkylarkModule(
    name = "JavaInfo",
    doc = "Encapsulates all information provided by Java rules",
    category = SkylarkModuleCategory.PROVIDER
)
@Immutable
public final class JavaInfo extends NativeInfo {

  public static final NativeProvider<JavaInfo> PROVIDER =
      new NativeProvider<JavaInfo>(JavaInfo.class, "JavaInfo") {};

  public static final JavaInfo EMPTY = JavaInfo.Builder.create().build();

  private static final ImmutableSet<Class<? extends TransitiveInfoProvider>> ALLOWED_PROVIDERS =
      ImmutableSet.of(
        JavaCompilationArgsProvider.class,
        JavaSourceJarsProvider.class,
        ProtoJavaApiInfoAspectProvider.class,
        JavaRuleOutputJarsProvider.class,
        JavaRunfilesProvider.class,
        JavaPluginInfoProvider.class
      );

  private final TransitiveInfoProviderMap providers;

  /** Returns the instance for the provided providerClass, or <tt>null</tt> if not present. */
  @Nullable
  public <P extends TransitiveInfoProvider> P getProvider(Class<P> providerClass) {
    return providers.getProvider(providerClass);
  }

  public TransitiveInfoProviderMap getProviders() {
    return providers;
  }

  /**
   * Merges the given providers into one {@link JavaInfo}. All the providers with the same type
   * in the given list are merged into one provider that is added to the resulting
   * {@link JavaInfo}.
   */
  public static JavaInfo merge(List<JavaInfo> providers) {
    List<JavaCompilationArgsProvider> javaCompilationArgsProviders =
        JavaInfo.fetchProvidersFromList(providers, JavaCompilationArgsProvider.class);
    List<JavaSourceJarsProvider> javaSourceJarsProviders =
        JavaInfo.fetchProvidersFromList(providers, JavaSourceJarsProvider.class);
    List<ProtoJavaApiInfoAspectProvider> protoJavaApiInfoAspectProviders =
        JavaInfo.fetchProvidersFromList(providers, ProtoJavaApiInfoAspectProvider.class);
    List<JavaRunfilesProvider> javaRunfilesProviders =
        JavaInfo.fetchProvidersFromList(providers, JavaRunfilesProvider.class);
    List<JavaPluginInfoProvider> javaPluginInfoProviders =
        JavaInfo.fetchProvidersFromList(providers, JavaPluginInfoProvider.class);

    Runfiles mergedRunfiles = Runfiles.EMPTY;
    for (JavaRunfilesProvider javaRunfilesProvider : javaRunfilesProviders) {
      Runfiles runfiles = javaRunfilesProvider.getRunfiles();
      mergedRunfiles = mergedRunfiles == Runfiles.EMPTY ? runfiles : mergedRunfiles.merge(runfiles);
    }

    return JavaInfo.Builder.create()
        .addProvider(
            JavaCompilationArgsProvider.class,
            JavaCompilationArgsProvider.merge(javaCompilationArgsProviders))
        .addProvider(
          JavaSourceJarsProvider.class, JavaSourceJarsProvider.merge(javaSourceJarsProviders))
        .addProvider(
            ProtoJavaApiInfoAspectProvider.class,
            ProtoJavaApiInfoAspectProvider.merge(protoJavaApiInfoAspectProviders))
        // When a rule merges multiple JavaProviders, its purpose is to pass on information, so
        // it doesn't have any output jars.
        .addProvider(JavaRuleOutputJarsProvider.class, JavaRuleOutputJarsProvider.builder().build())
        .addProvider(JavaRunfilesProvider.class, new JavaRunfilesProvider(mergedRunfiles))
        .addProvider(
            JavaPluginInfoProvider.class, JavaPluginInfoProvider.merge(javaPluginInfoProviders))
        .build();
  }

  /**
   * Returns a list of providers of the specified class, fetched from the given list of
   * {@link JavaInfo}s.
   * Returns an empty list if no providers can be fetched.
   * Returns a list of the same size as the given list if the requested providers are of type
   * JavaCompilationArgsProvider.
   */
  public static <C extends TransitiveInfoProvider> List<C> fetchProvidersFromList(
      Iterable<JavaInfo> javaProviders, Class<C> providersClass) {
    List<C> fetchedProviders = new LinkedList<>();
    for (JavaInfo javaInfo : javaProviders) {
      C provider = javaInfo.getProvider(providersClass);
      if (provider != null) {
        fetchedProviders.add(provider);
      }
    }
    return fetchedProviders;
  }

  /**
   * Returns a provider of the specified class, fetched from the specified target or, if not found,
   * from the JavaInfo of the given target. JavaInfo can be found as a declared provider
   * in SkylarkProviders.
   * Returns null if no such provider exists.
   *
   * <p>A target can either have both the specified provider and JavaInfo that encapsulates the
   * same information, or just one of them.</p>
   */
  @Nullable
  public static <T extends TransitiveInfoProvider> T getProvider(
      Class<T> providerClass, TransitiveInfoCollection target) {
    T provider = target.getProvider(providerClass);
    if (provider != null) {
      return provider;
    }
    JavaInfo javaInfo = (JavaInfo) target.get(JavaInfo.PROVIDER.getKey());
    if (javaInfo == null) {
      return null;
    }
    return javaInfo.getProvider(providerClass);
  }

  public static <T extends TransitiveInfoProvider> T getProvider(
      Class<T> providerClass, TransitiveInfoProviderMap providerMap) {
    T provider = providerMap.getProvider(providerClass);
    if (provider != null) {
      return provider;
    }
    JavaInfo javaInfo = (JavaInfo) providerMap.getProvider(JavaInfo.PROVIDER.getKey());
    if (javaInfo == null) {
      return null;
    }
    return javaInfo.getProvider(providerClass);
  }

  public static <T extends TransitiveInfoProvider> List<T> getProvidersFromListOfTargets(
      Class<T> providerClass, Iterable<? extends TransitiveInfoCollection> targets) {
    List<T> providersList = new ArrayList<>();
    for (TransitiveInfoCollection target : targets) {
      T provider = getProvider(providerClass, target);
      if (provider != null) {
        providersList.add(provider);
      }
    }
    return providersList;
  }

  /**
   * Returns a list of the given provider class with all the said providers retrieved from the
   * given {@link JavaInfo}s.
   */
  public static <T extends TransitiveInfoProvider> ImmutableList<T>
      getProvidersFromListOfJavaProviders(
          Class<T> providerClass, Iterable<JavaInfo> javaProviders) {
    ImmutableList.Builder<T> providersList = new ImmutableList.Builder<>();
    for (JavaInfo javaInfo : javaProviders) {
      T provider = javaInfo.getProvider(providerClass);
      if (provider != null) {
        providersList.add(provider);
      }
    }
    return providersList.build();
  }

  private JavaInfo(TransitiveInfoProviderMap providers) {
    super(PROVIDER);
    this.providers = providers;
  }

  @SkylarkCallable(
      name = "transitive_runtime_jars",
      doc = "Depset of runtime jars required by this target",
      structField = true
  )
  public SkylarkNestedSet getTransitiveRuntimeJars() {
    JavaCompilationArgsProvider provider = getProvider(JavaCompilationArgsProvider.class);
    NestedSet<Artifact> runtimeJars = provider == null
        ? NestedSetBuilder.<Artifact>stableOrder().build()
        : provider.getRecursiveJavaCompilationArgs().getRuntimeJars();
    return SkylarkNestedSet.of(Artifact.class, runtimeJars);
  }

  @SkylarkCallable(
      name = "transitive_compile_time_jars",
      doc = "Depset of compile time jars recusrively required by this target. See `compile_jars` "
          + "for more details.",
      structField = true
  )
  public SkylarkNestedSet getTransitiveCompileTimeJars() {
    JavaCompilationArgsProvider provider = getProvider(JavaCompilationArgsProvider.class);
    NestedSet<Artifact> compileTimeJars = provider == null
        ? NestedSetBuilder.<Artifact>stableOrder().build()
        : provider.getRecursiveJavaCompilationArgs().getCompileTimeJars();
    return SkylarkNestedSet.of(Artifact.class, compileTimeJars);
  }

  @SkylarkCallable(
      name = "compile_jars",
      doc = "Returns the compile time jars required by this target directly. They can be: <ul>"
          + "<li> interface jars (ijars), if an ijar tool was used, either by calling "
          + "java_common.create_provider(use_ijar=True, ...) or by passing --use_ijars on the "
          + "command line for native Java rules and `java_common.compile`</li>"
          + "<li> normal full jars, if no ijar action was requested</li>"
          + "<li> both ijars and normal full jars, if this provider was created by merging two or "
          + "more providers created with different ijar requests </li> </ul>",
      structField = true
  )
  public SkylarkNestedSet getCompileTimeJars() {
    JavaCompilationArgsProvider provider = getProvider(JavaCompilationArgsProvider.class);
    NestedSet<Artifact> compileTimeJars = provider == null
        ? NestedSetBuilder.<Artifact>stableOrder().build()
        : provider.getJavaCompilationArgs().getCompileTimeJars();
    return SkylarkNestedSet.of(Artifact.class, compileTimeJars);
  }

  @SkylarkCallable(
      name = "full_compile_jars",
      doc = "Returns the full compile time jars required by this target directly. They can be <ul>"
          + "<li> the corresponding normal full jars of the ijars returned by `compile_jars`<li>"
          + "<li> the normal full jars returned by `compile_jars`<li><ul>"
          + "Note: `compile_jars` can return a mix of ijars and normal full jars. In that case, "
          + "`full_compile_jars` returns the corresponding full jars of the ijars and the remaining"
          + "normal full jars in `compile_jars`.",
      structField = true
  )
  public SkylarkNestedSet getFullCompileTimeJars() {
    JavaCompilationArgsProvider provider = getProvider(JavaCompilationArgsProvider.class);
    NestedSet<Artifact> fullCompileTimeJars = provider == null
        ? NestedSetBuilder.<Artifact>stableOrder().build()
        : provider.getJavaCompilationArgs().getFullCompileTimeJars();
    return SkylarkNestedSet.of(Artifact.class, fullCompileTimeJars);
  }

  @SkylarkCallable(
      name = "source_jars",
      doc = "Returns a list of jar files containing all the uncompiled source files (including "
      + "those generated by annotations) from the target itself, i.e. NOT including the sources of "
      + "the transitive dependencies",
      structField = true
  )
  public SkylarkList<Artifact> getSourceJars() {
    JavaSourceJarsProvider provider = providers.getProvider(JavaSourceJarsProvider.class);
    ImmutableList<Artifact> sourceJars =
        provider == null ? ImmutableList.of() : provider.getSourceJars();
    return SkylarkList.createImmutable(sourceJars);
  }

  @SkylarkCallable(
    name = "outputs",
    doc = "Returns information about outputs of this Java target.",
    structField = true,
    allowReturnNones = true
  )
  public JavaRuleOutputJarsProvider getOutputJars() {
    return getProvider(JavaRuleOutputJarsProvider.class);
  }

  @Override
  public boolean equals(Object otherObject) {
    if (this == otherObject) {
      return true;
    }
    if (!(otherObject instanceof JavaInfo)) {
      return false;
    }

    JavaInfo other = (JavaInfo) otherObject;
    return providers.equals(other.providers);
  }

  @Override
  public int hashCode() {
    return providers.hashCode();
  }

  /**
   * A Builder for {@link JavaInfo}.
   */
  public static class Builder {
    TransitiveInfoProviderMapBuilder providerMap;

    private Builder(TransitiveInfoProviderMapBuilder providerMap) {
      this.providerMap = providerMap;
    }

    public static Builder create() {
      return new Builder(new TransitiveInfoProviderMapBuilder());
    }

    public static Builder copyOf(JavaInfo javaInfo) {
      return new Builder(
          new TransitiveInfoProviderMapBuilder().addAll(javaInfo.getProviders()));
    }

    public <P extends TransitiveInfoProvider> Builder addProvider(
        Class<P> providerClass, TransitiveInfoProvider provider) {
      Preconditions.checkArgument(ALLOWED_PROVIDERS.contains(providerClass));
      providerMap.put(providerClass, provider);
      return this;
    }

    public JavaInfo build() {
      return new JavaInfo(providerMap.build());
    }
  }
}
