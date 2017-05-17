import big.AsyncIOTest
import big.BigIOTest
import big.BigTest
import com.jtransc.BuildBackend
import com.jtransc.gen.dart.DartTarget
import jtransc.ProcessTest
import jtransc.micro.MicroHelloWorld
import org.junit.Ignore
import org.junit.Test

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

class DartTest : _Base() {
	override val DEFAULT_TARGET = DartTarget()

	//@Ignore
	@Test fun testMicroHelloWorldAsm() = testClass<MicroHelloWorld>(minimize = false, log = false, treeShaking = true, backend = BuildBackend.ASM)

	@Test fun testBigTest() = testClass<BigTest>(minimize = false, log = false, treeShaking = true, backend = BuildBackend.ASM)

	@Test fun testBigIO() = testClass<BigIOTest>(minimize = false, log = false, treeShaking = true)

	@Ignore("Process not implemented yet!")
	@Test fun testProcess() = testClass<ProcessTest>(minimize = false, log = false, treeShaking = true)

	@Test fun testAsyncIO() = testClass<AsyncIOTest>(minimize = false, log = false, treeShaking = true)
}
