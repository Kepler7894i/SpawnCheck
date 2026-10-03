package dev.spawncheck.forge;

import dev.spawncheck.client.SpawnCheckClient;
import dev.spawncheck.net.Payloads;

/** Receives the clientbound payloads. A separate class so the channel setup never touches client classes on a dedicated server. */
final class ClientHandlers {
	private ClientHandlers() {
	}

	static void onScanResult(final Payloads.ScanResult payload) {
		SpawnCheckClient.onScanResult(payload);
	}

	static void onFakePositions(final Payloads.FakePositions payload) {
		SpawnCheckClient.onFakePositions(payload);
	}
}
