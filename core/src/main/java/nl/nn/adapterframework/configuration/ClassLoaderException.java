/*
   Copyright 2021 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package nl.nn.adapterframework.configuration;

import nl.nn.adapterframework.configuration.classloaders.IConfigurationClassLoader;
import nl.nn.adapterframework.core.IbisException;

/**
 * Exception thrown by {@link ClassLoaderManager} when it fails to create an {@link IConfigurationClassLoader}.
 * 
 * @author Niels Meijer
 */
public class ClassLoaderException extends IbisException {

	public ClassLoaderException(String msg) {
		super(msg);
	}

	public ClassLoaderException(String msg, Throwable th) {
		super(msg, th);
	}

	public ClassLoaderException(Throwable e) {
		super(e);
	}
}
