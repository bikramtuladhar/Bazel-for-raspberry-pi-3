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

package com.google.devtools.build.buildjar;

import com.google.auto.value.AutoValue;
import com.google.common.base.Function;
import javax.annotation.Nullable;

/**
 * Holds information about the Bazel rule that created a certain jar.
 *
 * <p>Rules that use Aspects (http://www.bazel.build/docs/skylark/aspects.html) to compile jars will
 * result in 'aspect()' being populated.
 */
@AutoValue
public abstract class JarOwner {

  /** A long way to say 'JarOwner::label'. */
  public static final Function<JarOwner, String> LABEL =
      new Function<JarOwner, String>() {
        @Nullable
        @Override
        public String apply(@Nullable JarOwner jarOwner) {
          return jarOwner == null ? null : jarOwner.label();
        }
      };

  public abstract String label();

  @Nullable
  public abstract String aspect();

  public static JarOwner create(String label) {
    return new AutoValue_JarOwner(label, null);
  }

  public static JarOwner create(String label, String aspect) {
    return new AutoValue_JarOwner(label, aspect);
  }
}
