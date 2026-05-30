/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Scala Center, Lightbend, and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package sbt.inc

import sbt.internal.inc._
import sbt.io.IO.{ withTemporaryDirectory => withTmpDir }
import sbt.io.syntax._
import xsbti.compile.{ AnalysisStore, CompileAnalysis, DefaultExternalHooks, Inputs, Output }
import java.util.Optional

import xsbti.VirtualFile

class IncrementalCompilerSpec extends BaseCompilerSpec {
  override val logLevel = sbt.util.Level.Debug
  behavior.of("incremental compiler")

  it should "compile" in withTmpDir { tmp =>
    val comp = ProjectSetup.simple(tmp.toPath, Seq(SourceFiles.Good)).createCompiler()
    try {
      val result = comp.doCompile()
      assertExists(comp.output / "pkg" / "Good$.class")
      assert(!result.analysis.readStamps.getAllSourceStamps.isEmpty)
    } finally comp.close()
  }

  it should "not compile anything if source has not changed" in withTmpDir { tmp =>
    val classes = Seq(SourceFiles.Good, SourceFiles.Foo)
    val comp = ProjectSetup.simple(tmp.toPath, classes).createCompiler()
    try {
      val result = comp.doCompile()
      val result2 = comp.doCompile(_.withPreviousResult(comp.zinc.previousResult(result)))
      assert(!result2.hasModified)
    } finally comp.close()
  }

  it should "honour Lookup#shouldDoEarlyOutput" in withTmpDir { tmp =>
    val ext = new NoopExternalLookup { override def shouldDoEarlyOutput(a: CompileAnalysis) = true }
    val extHooks = new DefaultExternalHooks(Optional.of(ext), Optional.empty())
    def compilerSetupHelper(ps: ProjectSetup) = {
      val setup1 = ps.copy(scalacOptions = ps.scalacOptions :+ "-language:experimental.macros")
      val c1 = setup1.createCompiler()
      c1.copy(incOptions = c1.incOptions.withExternalHooks(extHooks))
    }
    val p1 = VirtualSubproject(tmp.toPath / "p1")
    val p2 = VirtualSubproject(tmp.toPath / "p2").dependsOn(p1)
    val c1 = compilerSetupHelper(p1.setup)
    val c2 = compilerSetupHelper(p2.setup)
    try {
      val s1 = s"object A { def f(c: scala.reflect.macros.blackbox.Context) = c.literalUnit }"
      val s2 = s"object B { def f: Unit = macro A.f }"
      c1.compile(StringVirtualFile("A.scala", s1))
      c2.compile(StringVirtualFile("B.scala", s2))
      assertExists(c1.earlyOutput)
      assertExists(c2.earlyOutput)
    } finally {
      c1.close()
      c2.close()
    }
  }

  it should "compile Java code" in withTmpDir { tmp =>
    val comp = ProjectSetup.simple(tmp.toPath, Seq(SourceFiles.NestedJavaClasses)).createCompiler()
    try {
      comp.doCompileWithStore()
      val result1 = comp.doCompileAllJavaWithStore()
      assertExists(comp.output / "NestedJavaClasses.class")
      assert(!result1.analysis.readStamps.getAllSourceStamps.isEmpty)
    } finally comp.close()
  }

  it should "compile all Java code" in withTmpDir { tempDir =>
    val c1 = VirtualSubproject(tempDir.toPath / "sub1").setup.createCompiler()
    try {
      val result = c1.compileAllJava(StringVirtualFile("A.java", "public class A {}"))
      assert(!result.analysis.readStamps.getAllSourceStamps.isEmpty)
      assertExists(c1.output / "A.class")
    } finally c1.close()
  }

  it should "compile all Java code in a mixed project" in withTmpDir { tempDir =>
    val c1 = VirtualSubproject(tempDir.toPath / "sub1").setup.createCompiler()
    try {
      val jf = StringVirtualFile("A.java", "public class A {}")
      val sf = StringVirtualFile("B.scala", "class B { val a = new A }")

      val result1 = c1.compile(jf, sf)
      assert(result1.analysis.readStamps.getAllSourceStamps.keySet.size == 2)

      val result2 = c1.compileAllJava(jf, sf)
      assert(!result2.analysis.readStamps.getAllSourceStamps.isEmpty)
      assertExists(c1.output / "A.class")
    } finally c1.close()
  }

  it should "trigger full compilation if extra changes" in withTmpDir { tempDir =>
    val classes = Seq(SourceFiles.Good, SourceFiles.Foo)
    val comp = ProjectSetup.simple(tempDir.toPath, classes).createCompiler()
    try {
      val cacheFile = tempDir / "target" / "inc_compile.zip"
      val fileStore = AnalysisStore.getCachedStore(FileAnalysisStore.binary(cacheFile))

      val result = comp.doCompileWithStore(fileStore)
      assert(result.hasModified)

      val result2 = comp.doCompileWithStore(fileStore)
      assert(!result2.hasModified)

      val setup = comp.setup.withExtra(Array())
      val result3 = comp.doCompileWithStore(fileStore, _.withSetup(setup))
      assert(result3.hasModified)
    } finally comp.close()
  }

  it should "delete all products if extra changes" in withTmpDir { tempDir =>
    val comp =
      ProjectSetup.simple(tempDir.toPath, Seq(SourceFiles.Good, SourceFiles.Foo)).createCompiler()
    val comp2 =
      ProjectSetup.simple(tempDir.toPath, Seq(SourceFiles.Foo)).createCompiler()
    try {
      val cacheFile = tempDir / "target" / "inc_compile.zip"
      val fileStore = AnalysisStore.getCachedStore(FileAnalysisStore.binary(cacheFile))
      comp.doCompileWithStore(fileStore)
      val newSetup = comp2.setup.withExtra(Array())
      comp2.doCompileWithStore(fileStore, _.withSetup(newSetup))
      assertNotExists(comp.output / "pkg" / "Good$.class")
    } finally comp.close()
  }

  it should "not trigger full compilation for small Scala changes in a mixed project" in withTmpDir {
    tmp =>
      val project = VirtualSubproject(tmp.toPath / "p1")
      val comp = project.setup.createCompiler()
      try {
        val s1 = "class A { def a = 1 }"
        val s1b = "class A { def a = 2 }"
        val s2 = "class B { def b = 1 }"
        val s3 = "class C { def c = 1 }"
        val s4 = "public class D { public int d = 1; }"
        val s5 = "public class E { public int e = 1; }"

        val f1 = StringVirtualFile("A.scala", s1)
        val f1b = StringVirtualFile("A.scala", s1b)
        val f2 = StringVirtualFile("B.scala", s2)
        val f3 = StringVirtualFile("C.scala", s3)
        val f4 = StringVirtualFile("D.java", s4)
        val f5 = StringVirtualFile("E.java", s5)

        comp.compile(f1, f2, f3, f4, f5)
        val result = comp.compile(f1b, f2, f3, f4, f5)
        assert(lastClasses(result.analysis.asInstanceOf[Analysis]) == Set("A", "D", "E"))
      } finally {
        comp.close()
      }
  }

  it should "track dependencies from nested inner Java classes" in withTmpDir { tmp =>
    val project = VirtualSubproject(tmp.toPath / "p1")
    val comp = project.setup.createCompiler()
    try {
      val s1 =
        "public class A { public Object i = new Object() { public Object ii = new Object() { public int i = B.b; }; }; }"
      val s2 = "public class B { public static int b = 1; }"
      val s2b = "public class B { public static int b = 1; public static int b2 = 1; }"
      val s3 = "public class C { public static int c = 1; }"

      val f1 = StringVirtualFile("A.java", s1)
      val f2 = StringVirtualFile("B.java", s2)
      val f2b = StringVirtualFile("B.java", s2b)
      val f3 = StringVirtualFile("C.java", s3)

      def compileJava(sources: VirtualFile*) = {
        def incrementalJavaInputs(sources: VirtualFile*)(in: Inputs): Inputs = {
          comp.withSrcs(sources.toArray)(
            in.withOptions(
              in.options
                .withEarlyOutput(Optional.empty[Output])
                // remove -YpickleXXX args
                .withScalacOptions(comp.scalacOptions.toArray)
            )
              .withSetup(
                in.setup.withIncrementalCompilerOptions(
                  // remove pipelining
                  in.setup.incrementalCompilerOptions.withPipelining(false)
                )
              )
          )
        }
        comp.doCompileWithStore(newInputs = incrementalJavaInputs(sources: _*))
      }

      val res1 = compileJava(f1, f2, f3)
      val res2 = compileJava(f1, f2b, f3)
      assert(recompiled(res1, res2) == Set("A", "B"))
    } finally {
      comp.close()
    }
  }

  it should "track dependencies on constants" in withTmpDir { tmp =>
    val project = VirtualSubproject(tmp.toPath / "p1")
    val comp = project.setup.createCompiler()
    try {
      val s1 = "object A { final val i = 1 }"
      val s1b = "object A { final val i = 2 }"
      val s2 = "class B { def i = A.i }"
      val s3 = "class C { def i = 3 }"

      val f1 = StringVirtualFile("A.scala", s1)
      val f1b = StringVirtualFile("A.scala", s1b)
      val f2 = StringVirtualFile("B.scala", s2)
      val f3 = StringVirtualFile("C.scala", s3)

      val res1 = comp.compile(f1, f2, f3)
      val res2 = comp.compile(f1b, f2, f3)
      assert(recompiled(res1, res2) == Set("A", "B"))
    } finally {
      comp.close()
    }
  }

  it should "not throw NullPointerException when passing -Xshow-phases to scalac" in withTmpDir {
    tmp =>
      val comp = ProjectSetup.simple(
        tmp.toPath,
        Seq(SourceFiles.Good),
        Seq("-Xshow-phases")
      ).createCompiler()
      try {
        assertThrows[CompileFailed] {
          comp.doCompile()
        }
      } finally comp.close()
  }

  it should "emit SourceInfos when incremental compilation fails" in withTmpDir {
    tmp =>
      val project = VirtualSubproject(tmp.toPath / "p1")
      val comp = project.setup.createCompiler()
      val s1 = "object A { final val i = 1"
      val f1 = StringVirtualFile("A.scala", s1)
      try {
        val exception = intercept[CompileFailed] {
          comp.compile(f1)
        }
        exception.sourceInfosOption match {
          case Some(sourceInfos) =>
            assert(
              !sourceInfos.getAllSourceInfos.isEmpty,
              "Expected non-empty source infos"
            )
          case None => fail("Expected sourceInfos")
        }
      } finally comp.close()
  }

  it should "recover from cycle failure when an intermediate class has stale class files" in
    withTmpDir { tmp =>
      // p1.Wrapper changes [T, C] → [T]. In p2, A directly uses Wrapper, B uses A.w
      // (Wrapper type via inference), C uses B.getW. Only A's source is updated.
      // C's class file is deleted to simulate "removed products" from build state restore.
      //
      // Bug: only A (source) and C (product) are initially invalidated. Cycle 1 compiles
      // A+C, but C fails because B.class still references Wrapper[Int, String].
      // Fix: on cycle failure, expand invalidation to direct dependents (B) and retry.
      //
      // The custom ExternalLookup suppresses library-stamp detection so B isn't caught
      // via Wrapper's changed binary stamp — simulating real cases where name hashing
      // misses transitive deps through complex type indirection.

      val p1 = VirtualSubproject(tmp.toPath / "p1")
      val p2 = VirtualSubproject(tmp.toPath / "p2").dependsOn(p1)
      val c1 = p1.setup.createCompiler()

      val ext = new NoopExternalLookup {
        override def changedBinaries(
            prev: CompileAnalysis
        ): Option[Set[xsbti.VirtualFileRef]] = Some(Set.empty)
      }
      val extHooks = new DefaultExternalHooks(Optional.of(ext), Optional.empty())
      val c2Base = p2.setup.createCompiler()
      val c2 = c2Base.copy(
        incOptions = c2Base.incOptions
          .withExternalHooks(extHooks)
          .withRecompileAllFraction(1.0) // disable fallback to full recompile
      )
      try {
        val wrapperV1 = StringVirtualFile(
          "Wrapper.scala",
          "package up\nclass Wrapper[T, C](val x: T)\n"
        )
        val aV1 = StringVirtualFile(
          "A.scala",
          "package down\nimport up.Wrapper\nclass A { val w = new Wrapper[Int, String](1) }\n"
        )
        val b = StringVirtualFile(
          "B.scala",
          "package down\nclass B { val a = new A; def getW = a.w }\n"
        )
        val cSrc = StringVirtualFile(
          "C.scala",
          "package down\nclass C { val w = (new B).getW }\n"
        )

        c1.compile(wrapperV1)
        val result1 = c2.compile(aV1, b, cSrc)

        val wrapperV2 = StringVirtualFile(
          "Wrapper.scala",
          "package up\nclass Wrapper[T](val x: T)\n"
        )
        val aV2 = StringVirtualFile(
          "A.scala",
          "package down\nimport up.Wrapper\nclass A { val w = new Wrapper[Int](1) }\n"
        )

        c1.compile(wrapperV2)
        java.nio.file.Files.deleteIfExists(p2.classesDir.resolve("down/C.class"))
        val result2 = c2.compile(aV2, b, cSrc)

        val recompiledSet = recompiled(result1, result2)
        assert(recompiledSet == Set("down.A", "down.B", "down.C"), recompiledSet)
      } finally {
        c1.close()
        c2.close()
      }
    }
}
