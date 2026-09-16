package org.restexpress.util;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

public class SslUtil
{
	public static SslContext loadContext(String keyStore, char[] filePassword, char[] keyPassword) throws Exception
	{
		FileInputStream fin = null;

		try
		{
			KeyStore ks = KeyStore.getInstance("JKS");
			fin = new FileInputStream(keyStore);
			ks.load(fin, filePassword);

			KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
			kmf.init(ks, keyPassword);

			return SslContextBuilder
				.forServer(kmf)
				.protocols("TLS")
				.sslProvider(SslProvider.JDK)
				.build();
		}
		finally
		{			
			if (null != fin)
			{
				try
				{
					fin.close();
				}
				catch (IOException e)
				{
				}
			}
		}
	}
}
