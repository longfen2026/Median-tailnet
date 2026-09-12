package com.xinyv.median;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class TailnetStatusTest {
    @Test
    public void equivalentPollDoesNotRequestUiRefresh() {
    TailnetStatus status = TailnetStatus.parse(
        "{\"BackendState\":\"NeedsLogin\","
        + "\"AuthURL\":\"https://login.tailscale.com/a/example\"}");

    assertFalse(TailnetManager.visibleStatusChanged(TailnetManager.State.NEEDS_LOGIN,
        status.authUrl, null, status));
    assertTrue(TailnetManager.visibleStatusChanged(TailnetManager.State.CONNECTING,
        status.authUrl, null, status));
    assertTrue(TailnetManager.visibleStatusChanged(TailnetManager.State.NEEDS_LOGIN,
        null, null, status));
    }

    @Test
    public void runningPollDoesNotDowngradeAppliedProxyState() {
    TailnetStatus status = TailnetStatus.parse(
        "{\"BackendState\":\"Running\",\"AuthURL\":\"\"}");

    assertEquals(TailnetManager.State.RUNNING,
        TailnetManager.stateForStatus(TailnetManager.State.RUNNING, status));
    assertEquals(TailnetManager.State.CONNECTING,
        TailnetManager.stateForStatus(TailnetManager.State.STARTING, status));
    }

    @Test
    public void runningStatusPermitsTailnetTransport() {
        TailnetStatus status = TailnetStatus.parse(
                "{\"BackendState\":\"Running\",\"AuthURL\":\"\"}");

        assertTrue(status.isRunning());
        assertFalse(status.needsLogin());
        assertNull(status.authUrl);
    }

    @Test
    public void needsLoginRetainsValidatedHttpsAuthorizationUrl() {
        TailnetStatus status = TailnetStatus.parse(
                "{\"BackendState\":\"NeedsLogin\","
                + "\"AuthURL\":\"https://login.tailscale.com/a/example\"}");

        assertFalse(status.isRunning());
        assertTrue(status.needsLogin());
        assertEquals("https://login.tailscale.com/a/example", status.authUrl);
    }

    @Test
    public void unsafeAuthorizationUrlIsDiscarded() {
        TailnetStatus status = TailnetStatus.parse(
                "{\"BackendState\":\"NeedsLogin\","
                + "\"AuthURL\":\"http://login.tailscale.com/a/example\"}");

        assertTrue(status.needsLogin());
        assertNull(status.authUrl);
    }
}