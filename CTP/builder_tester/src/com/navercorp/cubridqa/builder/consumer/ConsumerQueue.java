package com.navercorp.cubridqa.builder.consumer;

import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;

/**
 * The QAHome consumers' ActiveMQ queue that Builder-Tester jobs go to. A message is what
 * start_consumer.sh takes: its string properties become the environment of the -exec script
 * (common/sched/init.sh), so every value must be a single word.
 */
public class ConsumerQueue {
    private final String url;
    private final String user;
    private final String password;
    private final String queue;

    public ConsumerQueue(String url, String user, String password, String queue) {
        this.url = url;
        this.user = user == null || user.isEmpty() ? null : user;
        this.password = password == null || password.isEmpty() ? null : password;
        this.queue = queue;
    }

    public String getQueueName() {
        return queue;
    }

    public void publish(Map<String, String> properties, String text, int priority, long timeToLiveMs) throws JMSException {
        Connection connection = connect();
        try {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(session.createQueue(queue));
            TextMessage message = session.createTextMessage(text);
            for (Map.Entry<String, String> property : properties.entrySet()) {
                message.setStringProperty(property.getKey(), property.getValue());
            }
            producer.send(message, DeliveryMode.PERSISTENT, priority, timeToLiveMs);
        } finally {
            connection.close();
        }
    }

    /**
     * Removes the messages of a request that no consumer took yet, so a finished request does not
     * make idle VMs start a runner only to find nothing to do. Returns how many were removed.
     */
    public int purge(String property, String value) throws JMSException {
        String selector = selector(property, value);
        Connection connection = connect();
        try {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue destination = session.createQueue(queue);
            MessageConsumer consumer = session.createConsumer(destination, selector);
            int removed = 0;
            Message message;
            while ((message = consumer.receive(1000)) != null) {
                removed++;
            }
            consumer.close();
            return removed;
        } finally {
            connection.close();
        }
    }

    /** The values of readProperty on the messages with property = value still in the queue. */
    public Set<String> browse(String property, String value, String readProperty) throws JMSException {
        String selector = selector(property, value);
        Connection connection = connect();
        try {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            QueueBrowser browser = session.createBrowser(session.createQueue(queue), selector);
            Set<String> values = new HashSet<>();
            Enumeration<?> messages = browser.getEnumeration();
            while (messages.hasMoreElements()) {
                Object message = messages.nextElement();
                if (message instanceof Message) {
                    String v = ((Message) message).getStringProperty(readProperty);
                    if (v != null) {
                        values.add(v);
                    }
                }
            }
            browser.close();
            return values;
        } finally {
            connection.close();
        }
    }

    private static String selector(String property, String value) {
        if (!property.matches("[A-Z_]+") || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Unsafe selector: " + property + "=" + value);
        }
        return property + " = '" + value + "'";
    }

    private Connection connect() throws JMSException {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(user, password, url);
        Connection connection = factory.createConnection();
        connection.start();
        return connection;
    }
}
