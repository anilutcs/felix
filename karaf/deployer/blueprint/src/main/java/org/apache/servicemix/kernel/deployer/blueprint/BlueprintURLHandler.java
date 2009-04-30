/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.servicemix.kernel.deployer.blueprint;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.url.AbstractURLStreamHandlerService;

/**
 * A URL handler that will transform a JBI artifact to an OSGi bundle
 * on the fly.  Needs to be registered in the OSGi registry.
 */
public class BlueprintURLHandler extends AbstractURLStreamHandlerService {

	private static Log logger = LogFactory.getLog(BlueprintURLHandler.class);

	private static String SYNTAX = "blueprint: bp-xml-uri";

	private URL blueprintXmlURL;

    /**
     * Open the connection for the given URL.
     *
     * @param url the url from which to open a connection.
     * @return a connection on the specified URL.
     * @throws IOException if an error occurs or if the URL is malformed.
     */
    @Override
	public URLConnection openConnection(URL url) throws IOException {
		if (url.getPath() == null || url.getPath().trim().length() == 0) {
			throw new MalformedURLException ("Path can not be null or empty. Syntax: " + SYNTAX );
		}
		blueprintXmlURL = new URL(url.getPath());

		logger.debug("Spring xml URL is: [" + blueprintXmlURL + "]");
		return new Connection(url);
	}
	
	public URL getBlueprintXmlURL() {
		return blueprintXmlURL;
	}

    public class Connection extends URLConnection {

        public Connection(URL url) {
            super(url);
        }

        @Override
        public void connect() throws IOException {
        }

        @Override
        public InputStream getInputStream() throws IOException {
            try {
                final File f = File.createTempFile("smx", "xml");
                FileOutputStream os = new FileOutputStream(f);
                BlueprintTransformer.transform(blueprintXmlURL, os);
                os.close();
                return new FileInputStream(f) {
                    public void close() throws IOException {
                        super.close();
                        f.delete();
                    }
                };
            } catch (Exception e) {
                logger.error("Error opening spring xml url", e);
                throw (IOException) new IOException("Error opening spring xml url").initCause(e);
            }
        }
    }

}
