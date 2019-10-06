/*
  Copyright 1995-2015 Esri

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

  For additional information, contact:
  Environmental Systems Research Institute, Inc.
  Attn: Contracts Dept
  380 New York Street
  Redlands, California, USA 92373

  email: contracts@esri.com
*/

package com.esri.geoevent.transport.mqtt;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

import java.net.MalformedURLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import com.esri.ges.core.component.ComponentException;
import com.esri.ges.core.component.RunningException;
import com.esri.ges.core.component.RunningState;
import com.esri.ges.core.property.Property;
import com.esri.ges.framework.i18n.BundleLogger;
import com.esri.ges.framework.i18n.BundleLoggerFactory;
import com.esri.ges.transport.InboundTransportBase;
import com.esri.ges.transport.TransportDefinition;

public class MqttInboundTransport extends InboundTransportBase implements Runnable
{

	private static final BundleLogger	log			= BundleLoggerFactory.getLogger(MqttInboundTransport.class);

	private volatile Thread								thread	= null;
	private int												port;
	private String										host;
	private boolean										ssl;
	private int											altPort;
	private String										altHost;
	private boolean										altSsl;
	private String										topic;
	private int												qos;
	private boolean										autoReconnect;
	private boolean										cleanSession;
	private boolean										resubOnRecon;
	private boolean										unsubOnStop;
	private String									predefClientId;
	private MqttClient								mqttClient;
	private String										username;
	private char[]										password;
	private String										generatedClientId;

	public MqttInboundTransport(TransportDefinition definition) throws ComponentException
	{
		super(definition);
	}

	@Override
	@SuppressWarnings("incomplete-switch")
	public void start() throws RunningException
	{
		try
		{
			switch (getRunningState())
			{
				case STARTING:
				case STARTED:
				case STOPPING:
					return;
			}
			setRunningState(RunningState.STARTING);
			thread = new Thread(this);
			thread.start();
		}
		catch (Exception e)
		{
			log.error("UNEXPECTED_ERROR_STARTING", e);
			stop();
		}
	}

	@Override
	public void run()
	{
		this.receiveData();
	}

	private void receiveData()
	{
		if (this.thread == null) return; // already stopped before even scheduled
		try
		{
			applyProperties();
			setRunningState(RunningState.STARTED);

			String url = (ssl ? "ssl://" : "tcp://") + host + ":" + Integer.toString(port);
			String sessionClientId = predefClientId != null ? predefClientId : 
				generatedClientId != null ? generatedClientId :
					(generatedClientId = MqttClient.generateClientId());
			
			final MqttClient client = new MqttClient(url, sessionClientId, new MemoryPersistence());
			client.setCallback(new MqttCallbackExtended()
				{
					@Override
					public void connectComplete(boolean reconnect, String serverURI)
					{
						log.info((reconnect ? "Reconnected to " : "Connected to ") + serverURI);

						if (client == mqttClient && getRunningState() == RunningState.STARTED)
						{
							try
							{
								// this may be redundant if clean session is not selected, and
								// may cause any retained messages to be resent unnecessarily,
								// but this is a small price to pay for renewing the subscription.
                // todo test other options, but for now recommend cleanSession,
                // or when using preset session id, try without resubOnRecon
								if (!reconnect || cleanSession || resubOnRecon)
                {
									client.subscribe(topic, qos);
                }
							}
							catch (MqttException e)
							{
								log.error("Error in subscribe after connecting to MQTT broker.", e);
								if (client == mqttClient && getRunningState() == RunningState.STARTED)
								{
									setRunningState(RunningState.ERROR);
								}
							}
						}
					}
					
					@Override
					public void messageArrived(String topic, MqttMessage message) throws Exception
					{
						if (client == mqttClient)
						{
							try
							{
								receive(message.getPayload());
							}
							catch (RuntimeException e)
							{
								log.error("RuntimeException in messageArrived for MQTT inbound transport", e);
								//e.printStackTrace();
							}
						}
					}

					@Override
					public void deliveryComplete(IMqttDeliveryToken token)
					{
						// not used
					}

					@Override
					public void connectionLost(Throwable cause)
					{
						if (autoReconnect)
						{
							log.warn("CONNECTION_LOST", cause.getLocalizedMessage());
						}
						else
						{
							// in this case there is no attempt to reconnect, so error out
							log.error("CONNECTION_LOST", cause.getLocalizedMessage());
							if (mqttClient == client && getRunningState() == RunningState.STARTED)
							{
								setRunningState(RunningState.ERROR);
							}
						}
					}
				});

			MqttConnectOptions options = new MqttConnectOptions();

			// Connect with username and password if both are available.
			if (username != null && password != null && !username.isEmpty() && password.length > 0)
			{
				options.setUserName(username);
				options.setPassword(password);
			}
			
			if (altHost != null)
			{
				String altUrl = (altSsl ? "ssl://" : "tcp://") + altHost + ":" + Integer.toString(altPort);
				options.setServerURIs(new String[] { url, altUrl });
			}

			if (ssl || (altHost != null && altSsl))
			{
				// Support TLS only (1.0-1.2) as even SSL 3.0 has well known exploits
				java.util.Properties sslProperties = new java.util.Properties();
				sslProperties.setProperty("com.ibm.ssl.protocol", "TLS");
				options.setSSLProperties(sslProperties);
			}

			options.setCleanSession(cleanSession);
			options.setAutomaticReconnect(autoReconnect);

			boolean connecting = false;

			synchronized (this)
			{
				if (thread == Thread.currentThread())
				{
					mqttClient = client; // remember initialized client
					connecting = true;
				}
			}
			
			if (connecting)
			{
				client.connect(options);
				// subscribe moved to connectComplete callback
			}
			else
			{
				client.close();
			}
		}
		catch (Throwable ex)
		{
			log.error("UNEXPECTED_ERROR", ex);
			setRunningState(RunningState.ERROR);
		}
	}

	private void receive(byte[] bytes)
	{
		if (bytes != null && bytes.length > 0)
		{
			String str = new String(bytes);
			str = str + '\n';
			byte[] newBytes = str.getBytes();

			ByteBuffer bb = ByteBuffer.allocate(newBytes.length);
			try
			{
				bb.put(newBytes);
				bb.flip();
				byteListener.receive(bb, "");
				bb.clear();
			}
			catch (BufferOverflowException boe)
			{
				log.error("BUFFER_OVERFLOW_ERROR", boe);
				bb.clear();
				setRunningState(RunningState.ERROR);
			}
			catch (Exception e)
			{
				log.error("UNEXPECTED_ERROR2", e);
				stop();
				setRunningState(RunningState.ERROR);
			}
		}
	}

	private void applyProperties() throws Exception
	{
		
		Pattern mqttUrlPattern = Pattern.compile("^(?:(tcp|ssl)://)?([-.a-z0-9]+)(?::([0-9]+))?$", Pattern.CASE_INSENSITIVE);
		
		ssl = false;
		port = 1883;
		host = "iot.eclipse.org"; // default
		if (getProperty("host").isValid())
		{
			String value = (String) getProperty("host").getValue();
			if (!value.trim().equals(""))
			{
				Matcher matcher = mqttUrlPattern.matcher(value);
				if (matcher.matches())
				{
					ssl = "ssl".equalsIgnoreCase(matcher.group(1));
					host = matcher.group(2);
					port = matcher.start(3) > -1 ? Integer.parseInt(matcher.group(3)) :
									ssl ? 8883 : 1883; 
				}
				else
				{
					throw new MalformedURLException("Invalid MQTT Host URL");
				}
			}
		}

		altSsl = false;
		altPort = 1883;
		altHost = null; // none by default
		Property altHostProp = getProperty("alternateHost");
		if (altHostProp != null && altHostProp.isValid())
		{
			String value = (String) altHostProp.getValue();
			if (value != null && !value.trim().equals(""))
			{
				Matcher matcher = mqttUrlPattern.matcher(value);
				if (matcher.matches())
				{
					altSsl = "ssl".equalsIgnoreCase(matcher.group(1));
					altHost = matcher.group(2);
					altPort = matcher.start(3) > -1 ? Integer.parseInt(matcher.group(3)) :
									altSsl ? 8883 : 1883; 
				}
				else
				{
					throw new MalformedURLException("Invalid MQTT Alternate Host URL");
				}
			}
		}
		
		topic = "topic/sensor"; // default
		if (getProperty("topic").isValid())
		{
			String value = (String) getProperty("topic").getValue();
			if (!value.trim().equals(""))
			{
				topic = value;
			}
		}

		//Get the username as a simple String.
		username = null;
		if (getProperty("username").isValid())
		{
			String value = (String) getProperty("username").getValue();
			if (value != null)
			{
				username = value.trim();
			}
		}

		//Get the password as a DecryptedValue an convert it to an Char array.
		password = null;
		if (getProperty("password").isValid())
		{
			String value = (String) getProperty("password").getDecryptedValue();
			if (value != null)
			{
				password = value.toCharArray();
			}
		}

		qos = 0;
		if (getProperty("qos").isValid()) {
			try
			{
				int value = Integer.parseInt(getProperty("qos").getValueAsString());
				if ((value >= 0) && (value <= 2)) {
					qos = value;
				}
			}
			catch (NumberFormatException e)
			{
				throw e; // shouldn't ever happen
			}
		}
		
		autoReconnect = !getProperty("autoReconnect").isValid() ||
			Boolean.TRUE.equals(getProperty("autoReconnect").getValue());
		
		predefClientId = null; // default
		Property predefClientIdProp  = getProperty("predefClientId");
		if (predefClientIdProp != null && predefClientIdProp.isValid())
		{
			String value = predefClientIdProp.getValueAsString();
			if (value != null && !(value = value.trim()).isEmpty())
			{
				predefClientId = value;
			}
		}
		
 		Property cleanSessionProp = getProperty("cleanSession");
		cleanSession = (cleanSessionProp == null) || !cleanSessionProp.isValid() ||
			Boolean.TRUE.equals(cleanSessionProp.getValue());
		
 		Property unsubOnStopProp = getProperty("unsubOnStop");
		unsubOnStop = !cleanSession && (unsubOnStopProp == null || !unsubOnStopProp.isValid()
				|| Boolean.TRUE.equals(unsubOnStopProp.getValue()));
		
		Property resubOnReconProp = cleanSession ? null : getProperty("resubOnRecon");
		resubOnRecon = (resubOnReconProp == null || !resubOnReconProp.isValid()
				|| Boolean.TRUE.equals(resubOnReconProp.getValue()));
	}

	@Override
	public synchronized void stop()
	{
		setRunningState(RunningState.STOPPING);
		try
		{
			this.thread = null; // primitive cancellation signal
			if (this.mqttClient != null)
			{
				if (!cleanSession && unsubOnStop && topic != null)
				{
					this.mqttClient.unsubscribe(topic);
				}
				
				this.mqttClient.disconnect();
				this.mqttClient.close();
			}
		}
		catch (MqttException ex)
		{
			log.error("UNABLE_TO_CLOSE", ex);
		}
		setRunningState(RunningState.STOPPED);
	}

	@Override
	public boolean isClusterable()
	{
		return false;
	}
}
