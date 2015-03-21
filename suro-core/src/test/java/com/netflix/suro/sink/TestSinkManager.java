package com.netflix.suro.sink;

import com.google.common.collect.ImmutableMap;
import com.netflix.config.ConfigurationManager;
import com.netflix.suro.message.MessageContainer;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;

public class TestSinkManager {

    private static class MockSink implements Sink {
        private final AtomicInteger openAttempts = new AtomicInteger(0);
        private volatile boolean isOpened = false;

        @Override
        public void writeTo(MessageContainer message) {

        }

        @Override
        public void open() {
            if(openAttempts.incrementAndGet() >= 3) {
                isOpened = true;
            } else {
                throw new RuntimeException("cannot open sink");
            }
        }

        @Override
        public void close() {
            isOpened = false;
        }

        @Override
        public String recvNotice() {
            return null;
        }

        @Override
        public String getStat() {
            return null;
        }

        @Override
        public long getNumOfPendingMessages() {
            return 0;
        }

        @Override
        public boolean isOpened() {
            return isOpened;
        }
    }

    @Test
    public void testSinkOpenFailure() throws Exception {
        // override the interval from default 60s to 1s
        ConfigurationManager.getConfigInstance().setProperty("suro.SinkManager.sinkCheckInterval", "1");
        SinkManager sinkManager = new SinkManager();

        MockSink sink1 = new MockSink();
        sinkManager.initialSet(ImmutableMap.<String, Sink>of("sink1", sink1));
        sinkManager.initialStart();

        Assert.assertFalse(sink1.isOpened());
        Assert.assertEquals(1, sink1.openAttempts.get());
        assertThat(sinkManager.getSink("sink1"), Matchers.<Sink>sameInstance(sink1));

        Thread.sleep(1000 * 5);

        Assert.assertTrue(sink1.isOpened());
        Assert.assertEquals(3, sink1.openAttempts.get());
        assertThat(sinkManager.getSink("sink1"), Matchers.<Sink>sameInstance(sink1));

        MockSink sink2 = new MockSink();
        sinkManager.set(ImmutableMap.<String, Sink>of("sink2", sink2));

        Assert.assertFalse(sink1.isOpened());
        Assert.assertEquals(3, sink1.openAttempts.get());
        Assert.assertNull(sinkManager.getSink("sink1"));

        Assert.assertFalse(sink2.isOpened());
        Assert.assertEquals(1, sink2.openAttempts.get());
        assertThat(sinkManager.getSink("sink2"), Matchers.<Sink>sameInstance(sink2));

        Thread.sleep(1000 * 5);

        Assert.assertTrue(sink2.isOpened());
        Assert.assertEquals(3, sink2.openAttempts.get());
        assertThat(sinkManager.getSink("sink2"), Matchers.<Sink>sameInstance(sink2));
    }

    private static class MockSinkWithBlockingClose implements Sink {
        private volatile boolean isOpened = false;

        @Override
        public void writeTo(MessageContainer message) {
        }

        @Override
        public void open() {
            isOpened = true;
        }

        @Override
        public void close() {
            // block close for 1,000 ms
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // ignore
            }
            isOpened = false;
        }

        @Override
        public String recvNotice() {
            return null;
        }

        @Override
        public String getStat() {
            return null;
        }

        @Override
        public long getNumOfPendingMessages() {
            return 0;
        }

        @Override
        public boolean isOpened() {
            return isOpened;
        }
    }

    @Test
    public void testCloseBlocking() throws Exception {
        SinkManager sinkManager = new SinkManager();

        MockSinkWithBlockingClose sink1 = new MockSinkWithBlockingClose();
        sinkManager.initialSet(ImmutableMap.<String, Sink>of("sink1", sink1));
        sinkManager.initialStart();

        Assert.assertTrue(sink1.isOpened());
        assertThat(sinkManager.getSink("sink1"), Matchers.<Sink>sameInstance(sink1));

        MockSinkWithBlockingClose sink2 = new MockSinkWithBlockingClose();
        // this set should return immediately
        // because actually work is scheduled in background thread
        long start = System.currentTimeMillis();
        sinkManager.set(ImmutableMap.<String, Sink>of("sink2", sink2));
        long duration = System.currentTimeMillis() - start;
        Assert.assertTrue("duration = " + duration, duration < 50);

        // sleep for a short while to let the update finish
        Thread.sleep(200);
        // sink1 should be replaced by sink2 in manager
        Assert.assertNull(sinkManager.getSink("sink1"));
        Assert.assertNotNull(sinkManager.getSink("sink2"));
        // sink1 is NOT closed yet
        Assert.assertTrue(sink1.isOpened());
        // sink2 should be open
        Assert.assertTrue(sink2.isOpened());

        Thread.sleep(1000);
        // after 1,000 ms, sink1 should be closed
        Assert.assertFalse(sink1.isOpened());
        // sink2 should still be open
        Assert.assertTrue(sink2.isOpened());
    }
}
