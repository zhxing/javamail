/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2009 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.mail.pop3;

import java.util.Properties;
import java.lang.reflect.*;

import javax.mail.*;
import javax.mail.internet.*;
import java.io.IOException;
import java.io.EOFException;
import java.util.Collections;
import java.util.Map;

import com.sun.mail.util.PropUtil;

/**
 * A POP3 Message Store.  Contains only one folder, "INBOX".
 *
 * See the <a href="package-summary.html">com.sun.mail.pop3</a> package
 * documentation for further information on the POP3 protocol provider. <p>
 *
 * @author      Bill Shannon
 * @author      John Mani
 */
public class POP3Store extends Store {

    private String name = "pop3";		// my protocol name
    private int defaultPort = 110;		// default POP3 port
    private boolean isSSL = false;		// use SSL?

    private Protocol port = null;		// POP3 port for self
    private POP3Folder portOwner = null;	// folder owning port
    private String host = null;			// host
    private int portNum = -1;
    private String user = null;
    private String passwd = null;
    private boolean useStartTLS = false;
    private boolean requireStartTLS = false;
    private Map capabilities;

    // following set here and accessed by other classes in this package
    volatile Constructor messageConstructor = null;
    volatile boolean rsetBeforeQuit = false;
    volatile boolean disableTop = false;
    volatile boolean forgetTopHeaders = false;
    volatile boolean supportsUidl = true;

    public POP3Store(Session session, URLName url) {
	this(session, url, "pop3", false);
    }

    public POP3Store(Session session, URLName url,
				String name, boolean isSSL) {
	super(session, url);
	if (url != null)
	    name = url.getProtocol();
	this.name = name;
	if (!isSSL)
	    isSSL = PropUtil.getBooleanSessionProperty(session,
				"mail." + name + ".ssl.enable", false);
	if (isSSL)
	    this.defaultPort = 995;
	else
	    this.defaultPort = 110;
	this.isSSL = isSSL;

	rsetBeforeQuit = PropUtil.getBooleanSessionProperty(session,
				"mail." + name + ".rsetbeforequit", false);

	disableTop = PropUtil.getBooleanSessionProperty(session,
				"mail." + name + ".disabletop", false);

	forgetTopHeaders = PropUtil.getBooleanSessionProperty(session,
				"mail." + name + ".forgettopheaders", false);

	// mail.pop3.starttls.enable enables use of STLS command
	useStartTLS = PropUtil.getBooleanSessionProperty(session,
				"mail." + name + ".starttls.enable", false);

	// mail.pop3.starttls.required requires use of STLS command
	requireStartTLS = PropUtil.getBooleanSessionProperty(session,
				"mail." + name + ".starttls.required", false);

	String s = session.getProperty("mail." + name + ".message.class");
	if (s != null) {
	    if (session.getDebug())
		session.getDebugOut().println(
		    "DEBUG POP3: message class: " + s);
	    try {
		ClassLoader cl = this.getClass().getClassLoader();

		// now load the class
		Class messageClass = null;
		try {
		    // First try the "application's" class loader.
		    // This should eventually be replaced by
		    // Thread.currentThread().getContextClassLoader().
		    messageClass = Class.forName(s, false, cl);
		} catch (ClassNotFoundException ex1) {
		    // That didn't work, now try the "system" class loader.
		    // (Need both of these because JDK 1.1 class loaders
		    // may not delegate to their parent class loader.)
		    messageClass = Class.forName(s);
		}

		Class[] c = {javax.mail.Folder.class, int.class};
		messageConstructor = messageClass.getConstructor(c);
	    } catch (Exception ex) {
		if (session.getDebug())
		    session.getDebugOut().println(
			"DEBUG POP3: failed to load message class: " + ex);
	    }
	}
    }

    protected synchronized boolean protocolConnect(String host, int portNum,
		String user, String passwd) throws MessagingException {
		    
	// check for non-null values of host, password, user
	if (host == null || passwd == null || user == null)
	    return false;

	// if port is not specified, set it to value of mail.pop3.port
        // property if it exists, otherwise default to 110
        if (portNum == -1)
	    portNum = PropUtil.getIntSessionProperty(session,
				"mail." + name + ".port", -1);

	if (portNum == -1)
	    portNum = defaultPort;

	this.host = host;
	this.portNum = portNum;
	this.user = user;
	this.passwd = passwd;
	try {
	    port = getPort(null);
	} catch (EOFException eex) { 
		throw new AuthenticationFailedException(eex.getMessage());
	} catch (IOException ioex) { 
	    throw new MessagingException("Connect failed", ioex);
	}

	return true;
    }

    /**
     * Check whether this store is connected. Override superclass
     * method, to actually ping our server connection. <p>
     */
    /*
     * Note that we maintain somewhat of an illusion of being connected
     * even if we're not really connected.  This is because a Folder
     * can use the connection and close it when it's done.  If we then
     * ask whether the Store's connected we want the answer to be true,
     * as long as we can reconnect at that point.  This means that we
     * need to be able to reconnect the Store on demand.
     */
    public synchronized boolean isConnected() {
	if (!super.isConnected())
	    // if we haven't been connected at all, don't bother with
	    // the NOOP.
	    return false;
	try {
	    if (port == null)
		port = getPort(null);
	    else if (!port.noop())
		throw new IOException("NOOP failed");
	    return true;
	} catch (IOException ioex) {
	    // no longer connected, close it down
	    try {
		super.close();		// notifies listeners
	    } catch (MessagingException mex) {
		// ignore it
	    } finally {
		return false;
	    }
	}
    }

    synchronized Protocol getPort(POP3Folder owner) throws IOException {
	Protocol p;

	// if we already have a port, remember who's using it
	if (port != null && portOwner == null) {
	    portOwner = owner;
	    return port;
	}

	// need a new port, create it and try to login
	p = new Protocol(host, portNum, session.getDebug(),
	    session.getDebugOut(), session.getProperties(), "mail." + name,
	    isSSL);

	if (useStartTLS || requireStartTLS) {
	    if (p.hasCapability("STLS")) {
		p.stls();
		// refresh capabilities
		p.setCapabilities(p.capa());
	    } else if (requireStartTLS) {
		if (debug)
		    session.getDebugOut().println(
			"DEBUG POP3: STLS required but not supported");
		try {
		    p.quit();
		} catch (IOException ioex) {
		} finally {
		    throw new EOFException("STLS required but not supported");
		}
	    }
	}

	capabilities = p.getCapabilities();	// save for later, may be null

	/*
	 * If we haven't explicitly disabled use of the TOP command,
	 * and the server has provided its capabilities,
	 * and the server doesn't support the TOP command,
	 * disable the TOP command.
	 */
	if (!disableTop &&
		capabilities != null && !capabilities.containsKey("TOP")) {
	    disableTop = true;
	    session.getDebugOut().println(
		    "DEBUG POP3: server doesn't support TOP, disabling it");
	}

	supportsUidl = capabilities == null || capabilities.containsKey("UIDL");

	String msg = null;
	if ((msg = p.login(user, passwd)) != null) {
	    try {
		p.quit();
	    } catch (IOException ioex) {
	    } finally {
		throw new EOFException(msg);
	    }
	}

	/*
	 * If a Folder closes the port, and then a Folder
	 * is opened, the Store won't have a port.  In that
	 * case, the getPort call will come from Folder.open,
	 * but we need to keep track of the port in the Store
	 * so that a later call to Folder.isOpen, which calls
	 * Store.isConnected, will use the same port.
	 */
	if (port == null && owner != null) {
	    port = p;
	    portOwner = owner;
	}
	if (portOwner == null)
	    portOwner = owner;
	return p;
    }

    synchronized void closePort(POP3Folder owner) {
	if (portOwner == owner) {
	    port = null;
	    portOwner = null;
	}
    }

    public synchronized void close() throws MessagingException {
	try {
	    if (port != null)
		port.quit();
	} catch (IOException ioex) {
	} finally {
	    port = null;

	    // to set the state and send the closed connection event
	    super.close();
	}
    }

    public Folder getDefaultFolder() throws MessagingException {
	checkConnected();
	return new DefaultFolder(this);
    }

    /**
     * Only the name "INBOX" is supported.
     */
    public Folder getFolder(String name) throws MessagingException {
	checkConnected();
	return new POP3Folder(this, name);
    }

    public Folder getFolder(URLName url) throws MessagingException {
	checkConnected();
	return new POP3Folder(this, url.getFile());
    }

    /**
     * Return a Map of the capabilities the server provided,
     * as per RFC 2449.  If the server doesn't support RFC 2449,
     * an emtpy Map is returned.  The returned Map can not be modified.
     * The key to the Map is the upper case capability name as
     * a String.  The value of the entry is the entire String
     * capability line returned by the server. <p>
     *
     * For example, to check if the server supports the STLS capability, use:
     * <code>if (store.capabilities().containsKey("STLS")) ...</code>
     *
     * @return	Map of capabilities
     * @since	JavaMail 1.4.3
     */
    public Map capabilities() throws MessagingException {
	Map c = capabilities;	// atomic read of pointer
	if (c != null)
	    return Collections.unmodifiableMap(c);
	else
	    return Collections.EMPTY_MAP;
    }

    protected void finalize() throws Throwable {
	super.finalize();

	if (port != null)	// don't force a connection attempt
	    close();
    }

    private void checkConnected() throws MessagingException {
	if (!super.isConnected())
	    throw new MessagingException("Not connected");
    }
}
