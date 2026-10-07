#!/usr/bin/env python3
"""Run headless physics invariants with the existing offline Kotlin compiler cache."""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
cache = Path.home()/'.gradle/caches/modules-2/files-2.1'
patterns = ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.10/**/*.jar',
            'org.jetbrains.kotlin/kotlin-stdlib/2.4.10/**/*.jar',
            'org.jetbrains.kotlin/kotlin-script-runtime/2.4.10/**/*.jar',
            'org.jetbrains.kotlin/kotlin-reflect/1.8.21/**/*.jar',
            'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/**/*.jar',
            'org.jetbrains/annotations/**/*.jar']
jars = [str(p) for pattern in patterns for p in cache.glob(pattern)]
cp = ':'.join(jars)
with tempfile.TemporaryDirectory(prefix='cascade-physics-') as out:
    subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib','-no-reflect','-classpath',cp,'-d',out,
                    str(root/'core/src/main/java/dev/cascade/core/BeadSim.kt'),
                    str(root/'tests/PhysicsChecks.kt')],check=True)
    subprocess.run(['java','-cp',out+':'+cp,'PhysicsChecksKt'],check=True)
