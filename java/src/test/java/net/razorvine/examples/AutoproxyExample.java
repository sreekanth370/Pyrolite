package net.razorvine.examples;

import net.razorvine.pyro.Config;
import net.razorvine.pyro.PyroProxy;

import java.io.IOException;

/**
 * Simple example that shows the use of Pyro with an object returning proxies.
 *
 * @author Irmen de Jong (irmen@razorvine.net)
 */
public class AutoproxyExample {

	public static void main(String[] args) throws IOException {

		System.out.println("Testing Pyro autoproxy server (check the port number)...");
		System.out.println("Pyrolite version: "+Config.PYROLITE_VERSION);

		setConfig();

		PyroProxy p=new PyroProxy("localhost",45061,"example.autoproxy");	// change port number to whatever the server prints

		Object result=p.call("createSomething", 42);
		System.out.println("return value:");
		System.out.println(result);
		PyroProxy resultproxy=(PyroProxy)result;
		resultproxy.call("speak", "hello from java");

		p.close();
	}

	static void setConfig() {
		String tracedir=System.getenv("PYRO_TRACE_DIR");
		if(System.getProperty("PYRO_TRACE_DIR")!=null) {
			tracedir=System.getProperty("PYRO_TRACE_DIR");
		}
		Config.MSG_TRACE_DIR=tracedir;
	}
}
