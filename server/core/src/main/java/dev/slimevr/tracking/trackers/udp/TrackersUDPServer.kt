package dev.slimevr.tracking.trackers.udp

import com.jme3.math.FastMath
import dev.slimevr.NetworkProtocol
import dev.slimevr.VRServer
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerStatus
import io.eiren.util.Util
import io.eiren.util.collections.FastList
import io.eiren.util.logging.LogManager
import io.github.axisangles.ktmath.Quaternion.Companion.fromRotationVector
import io.github.axisangles.ktmath.Vector3
import org.apache.commons.lang3.ArrayUtils
import solarxr_protocol.rpc.ResetType
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Random
import java.util.function.Consumer

/**
 * Manages a list of connections, as well as necessary mappings to retrieve connections based on packets
 */
private class UDPConnectionManager {
	private val connections: MutableList<UDPDevice> = FastList()
	private val byHWID: MutableMap<String, UDPDevice> = HashMap()
	private val bySocketAddress: MutableMap<SocketAddress, UDPDevice> = HashMap()

	private fun removeFromAddressMap(connection: UDPDevice) {
		synchronized(connections) {
			bySocketAddress.remove(connection.address)
		}
	}

	private fun addToAddressMap(connection: UDPDevice) {
		synchronized(connections) {
			bySocketAddress[connection.address] = connection
		}
	}

	private fun addToConnectionsAndMaps(connection: UDPDevice): Int {
		synchronized(connections) {
			val i = connections.size
			connections.add(connection)

			addToAddressMap(connection)
			byHWID[connection.hardwareIdentifier] = connection

			return i
		}
	}

	/**
	 * Updates connection address, firmware, name, etc according to the packet
	 */
	private fun updateConnectionAttributes(connection: UDPDevice, handshakePacket: DatagramPacket, handshake: UDPPacket3Handshake) {
		connection.address = handshakePacket.socketAddress
		connection.lastPacketNumber = 0
		connection.ipAddress = handshakePacket.address

		connection.firmwareBuild = handshake.firmwareBuild
		connection.protocol = if (handshake.firmware?.isEmpty() == true) {
			// Only old owoTrack doesn't report firmware and have different packet IDs with SlimeVR
			NetworkProtocol.OWO_LEGACY
		} else {
			NetworkProtocol.SLIMEVR_RAW
		}

		// TODO: The missing slash in udp:// was intended because InetAddress.toString()
		// 		returns "hostname/address" but it wasn't known that if hostname is empty
		// 		string it just looks like "/address" lol.
		// 		Fixing this would break config!
		connection.descriptiveName = "udp:/${handshakePacket.address}"

		connection.name = handshake.macString?.let { "udp://$it" }
			?: connection.descriptiveName

		connection.firmwareFeatures = FirmwareFeatures()
	}

	/**
	 * Adapts a previous connection and returns it, if one exists
	 * A previous connection means a client with same MAC address, which may already have sensors/trackers set up
	 */
	private fun adaptPreviousConnection(handshakePacket: DatagramPacket, handshake: UDPPacket3Handshake): UDPDevice? {
		val existingConnection = synchronized(connections) {
			byHWID[handshake.macString ?: handshakePacket.address.hostAddress]
		}

		if (existingConnection == null) return null

		removeFromAddressMap(existingConnection)
		updateConnectionAttributes(existingConnection, handshakePacket, handshake)
		addToAddressMap(existingConnection)

		val i = getConnectionId(existingConnection)
		LogManager
			.info(
				"""
				[TrackerServer] Tracker $i switched over to address ${handshakePacket.socketAddress}.
				Board type: ${handshake.boardType},
				imu type: ${handshake.imuType},
				firmware: ${handshake.firmware} (${existingConnection.firmwareBuild}),
				mac: ${handshake.macString},
				name: ${existingConnection.name}
				""".trimIndent()
			)

		return existingConnection
	}

	/**
	 * Create a brand new connection. This must only be called if there is no previous connection (adaptPreviousConnection returned null)
	 */
	private fun createNewConnection(handshakePacket: DatagramPacket, handshake: UDPPacket3Handshake): UDPDevice {
		val connection = UDPDevice(
			handshakePacket.socketAddress,
			handshakePacket.address,
			handshake.macString ?: handshakePacket.address.hostAddress,
			handshake.boardType,
			handshake.mcuType
		)

		updateConnectionAttributes(connection, handshakePacket, handshake)

		VRServer.instance.deviceManager.addDevice(connection)
		val i = addToConnectionsAndMaps(connection)

		LogManager
			.info(
				"""
				[TrackerServer] Tracker $i added for address ${handshakePacket.socketAddress}.
				Board type: ${handshake.boardType},
				imu type: ${handshake.imuType},
				firmware: ${handshake.firmware} (${connection.firmwareBuild}),
				mac: ${handshake.macString},
				name: ${connection.name}
				""".trimIndent()
			)

		return connection
	}

	/**
	 * If there is a previous connection that should be re-used (e.g. same MAC), will adapt previous one and return it.
	 * Otherwise, it will create a brand new UDPDevice for this client.
	 */
	fun obtainConnectionForHandshake(handshakePacket: DatagramPacket, handshake: UDPPacket3Handshake): UDPDevice {
		if (handshake.macString == null) {
			LogManager.warning(
				"[TrackerServer] Client ${handshakePacket.socketAddress} doesn't report a valid MAC address. " +
					"If you are a firmware developer, please consider reporting a valid MAC to allow more robust session restoration."
			)
		}

		return adaptPreviousConnection(handshakePacket, handshake) ?: createNewConnection(handshakePacket, handshake)
	}

	/**
	 * Gets a given UDPDevice for the packet, based on address. May return null if it doesn't exist yet.
	 */
	fun getConnectionForPacket(packet: DatagramPacket): UDPDevice? {
		return synchronized(connections) {
			bySocketAddress[packet.socketAddress]
		}
	}

	fun hasActiveTrackers(): Boolean {
		return synchronized(connections) {
			connections.any { it.trackers.size > 0 }
		}
	}

	fun getConnectionId(connection: UDPDevice): Int? {
		return synchronized(connections) { connections.indexOf(connection) }
	}

	inline fun forEach(action: (UDPDevice) -> Unit) {
		synchronized(connections) {
			connections.forEach { action(it) }
		}
	}
}

/**
 * Receives trackers data by UDP using extended owoTrack protocol.
 */
class TrackersUDPServer(private val port: Int, name: String, private val trackersConsumer: Consumer<Tracker>) :
	Thread(name) {
	private val random = Random()
	private val connections = UDPConnectionManager()

	private val broadcastAddresses: List<InetSocketAddress> = try {
		NetworkInterface.getNetworkInterfaces().asSequence().filter {
			// Ignore loopback, PPP, virtual and disabled interfaces
			!it.isLoopback && it.isUp && !it.isPointToPoint && !it.isVirtual
		}.flatMap {
			it.interfaceAddresses.asSequence()
		}.map {
			// This ignores IPv6 addresses
			it.broadcast
		}.filterNotNull().map { InetSocketAddress(it, this.port) }.toList()
	} catch (e: Exception) {
		LogManager.severe("[TrackerServer] Can't enumerate network interfaces", e)
		emptyList()
	}
	private val parser = UDPProtocolParser()
	private val rcvBuffer = ByteArray(512)
	private val bb = ByteBuffer.wrap(rcvBuffer).order(ByteOrder.BIG_ENDIAN)

	// Gets initialized in this.run()
	private lateinit var socket: DatagramSocket

	private var prevDiscoveryPacketTime = System.currentTimeMillis()
	private fun sendDiscoveryPacketIfNecessary() {
		try {
			if (!connections.hasActiveTrackers()) {
				val discoveryPacketTime = System.currentTimeMillis()
				if (discoveryPacketTime - prevDiscoveryPacketTime >= 2000) {
					for (addr in broadcastAddresses) {
						bb.limit(bb.capacity())
						bb.rewind()
						parser.write(bb, null, UDPPacket0Heartbeat)
						socket.send(DatagramPacket(rcvBuffer, bb.position(), addr))
					}
					prevDiscoveryPacketTime = discoveryPacketTime
				}
			}
		} catch (e: IOException) {
			LogManager.warning("[TrackerServer] Error sending discovery packet", e)
		}
	}

	private fun processNextPacket() {
		var received: DatagramPacket? = null
		try {
			received = DatagramPacket(rcvBuffer, rcvBuffer.size)
			socket.receive(received)
			bb.limit(received.length)
			bb.rewind()
			val connection = connections.getConnectionForPacket(received)
			parser.parse(bb, connection)
				.filterNotNull()
				.forEach { processPacket(received, it, connection) }
		} catch (ignored: SocketTimeoutException) {
		} catch (e: Exception) {
			LogManager.warning(
				"[TrackerServer] Error parsing packet ${packetToString(received)}",
				e
			)
		}
	}

	private val serialBuffer = StringBuilder()
	private var lastKeepup = System.currentTimeMillis()
	private fun keepupExistingConnections() {
		if (lastKeepup + 500 < System.currentTimeMillis()) {
			lastKeepup = System.currentTimeMillis()

			connections.forEach { conn ->
				bb.limit(bb.capacity())
				bb.rewind()
				parser.write(bb, conn, UDPPacket1Heartbeat)
				socket.send(DatagramPacket(rcvBuffer, bb.position(), conn.address))
				if (conn.lastPacket + 1000 < System.currentTimeMillis()) {
					for (tracker in conn.trackers.values) {
						tracker.status = TrackerStatus.DISCONNECTED
					}
					if (!conn.timedOut) {
						conn.timedOut = true
						LogManager.info("[TrackerServer] Tracker timed out: $conn")
					}
				} else {
					conn.timedOut = false
					for (tracker in conn.trackers.values) {
						if (tracker.status == TrackerStatus.DISCONNECTED) {
							tracker.status = TrackerStatus.OK
						}
					}
				}
				if (conn.serialBuffer.isNotEmpty() &&
					conn.lastSerialUpdate + 500L < System.currentTimeMillis()
				) {
					serialBuffer
						.append('[')
						.append(conn.name)
						.append("] ")
						.append(conn.serialBuffer)
					println(serialBuffer)
					serialBuffer.setLength(0)
					conn.serialBuffer.setLength(0)
				}
				if (conn.lastPingPacketTime + 500 < System.currentTimeMillis()) {
					conn.lastPingPacketId = random.nextInt()
					conn.lastPingPacketTime = System.currentTimeMillis()
					bb.limit(bb.capacity())
					bb.rewind()
					bb.putInt(10)
					bb.putLong(0)
					bb.putInt(conn.lastPingPacketId)
					socket.send(DatagramPacket(rcvBuffer, bb.position(), conn.address))
				}
			}
		}
	}

	override fun run() {
		try {
			socket = DatagramSocket(port)
			socket.soTimeout = 250
			while (true) {
				sendDiscoveryPacketIfNecessary()
				processNextPacket()
				keepupExistingConnections()
			}
		} catch (e: Exception) {
			e.printStackTrace()
		} finally {
			Util.close(socket)
		}
	}

	private fun setUpSensor(connection: UDPDevice, trackerId: Int, sensorType: IMUType, sensorStatus: Int) {
		LogManager.info("[TrackerServer] Sensor $trackerId for ${connection.name} status: $sensorStatus")
		var imuTracker = connection.getTracker(trackerId)

		if (imuTracker == null) {
			imuTracker = Tracker(
				connection,
				VRServer.getNextLocalTrackerId(),
				connection.name + "/" + trackerId,
				"IMU Tracker " + MessageDigest.getInstance("SHA-256")
					.digest(connection.hardwareIdentifier.toByteArray(StandardCharsets.UTF_8)).toString().subSequence(3, 8),
				null,
				trackerNum = trackerId,
				hasRotation = true,
				hasAcceleration = true,
				userEditable = true,
				imuType = sensorType,
				allowFiltering = true,
				needsReset = true,
				needsMounting = true
			)
			connection.trackers[trackerId] = imuTracker
			trackersConsumer.accept(imuTracker)
			LogManager.info("[TrackerServer] Added sensor $trackerId for ${connection.name}, type $sensorType")
		}

		val status = UDPPacket15SensorInfo.getStatus(sensorStatus)
		if (status != null) imuTracker.status = status
	}

	private fun handleHandshake(received: DatagramPacket, packet: UDPPacket3Handshake) {
		LogManager.info("[TrackerServer] Handshake received from ${received.address}:${received.port}")
		val connection = connections.obtainConnectionForHandshake(received, packet)

		if (connection.protocol == NetworkProtocol.OWO_LEGACY || connection.firmwareBuild < 9) {
			// Set up new sensor for older firmware.
			// Firmware after 7 should send sensor status packet and sensor
			// will be created when it's received
			setUpSensor(connection, 0, packet.imuType, 1)
		}

		bb.limit(bb.capacity())
		bb.rewind()
		parser.writeHandshakeResponse(bb, connection)
		socket.send(DatagramPacket(rcvBuffer, bb.position(), connection.address))
	}

	private fun processPacket(received: DatagramPacket, packet: UDPPacket, connection: UDPDevice?) {
		val tracker: Tracker?
		when (packet) {
			is UDPPacket0Heartbeat, is UDPPacket1Heartbeat -> {}
			is UDPPacket3Handshake -> handleHandshake(received, packet)
			is RotationPacket -> {
				var rot = packet.rotation
				rot = AXES_OFFSET.times(rot)
				tracker = connection?.getTracker(packet.sensorId)
				if (tracker == null) return
				tracker.setRotation(rot)
				tracker.dataTick()
			}
			is UDPPacket17RotationData -> {
				tracker = connection?.getTracker(packet.sensorId)
				if (tracker == null) return
				var rot17 = packet.rotation
				rot17 = AXES_OFFSET * rot17
				when (packet.dataType) {
					UDPPacket17RotationData.DATA_TYPE_NORMAL -> {
						tracker.setRotation(rot17)
						tracker.dataTick()
						// tracker.calibrationStatus = rotationData.calibrationInfo;
						// Not implemented in server
					}

					UDPPacket17RotationData.DATA_TYPE_CORRECTION -> {
// 						tracker.rotMagQuaternion.set(rot17);
// 						tracker.magCalibrationStatus = rotationData.calibrationInfo;
// 						tracker.hasNewCorrectionData = true;
						// Not implemented in server
					}
				}
			}
			is UDPPacket18MagnetometerAccuracy -> {}
			is UDPPacket4Acceleration -> {
				tracker = connection?.getTracker(packet.sensorId)
				if (tracker == null) return
				// Switch x and y around to adjust for different axes
				tracker.setAcceleration(Vector3(packet.acceleration.y, packet.acceleration.x, packet.acceleration.z))
			}
			is UDPPacket10PingPong -> {
				if (connection == null) return
				if (connection.lastPingPacketId == packet.pingId) {
					for (t in connection.trackers.values) {
						t.ping = (System.currentTimeMillis() - connection.lastPingPacketTime).toInt() / 2
						t.dataTick()
					}
				} else {
					LogManager.debug(
						"[TrackerServer] Wrong ping id ${packet.pingId} != ${connection.lastPingPacketId}"
					)
				}
			}

			is UDPPacket11Serial -> {
				if (connection == null) return
				println("[${connection.name}] ${packet.serial}")
			}

			is UDPPacket12BatteryLevel -> connection?.trackers?.values?.forEach {
				it.batteryVoltage = packet.voltage
				it.batteryLevel = packet.level * 100
			}

			is UDPPacket13Tap -> {
				tracker = connection?.getTracker(packet.sensorId)
				if (tracker == null) return
				LogManager.info(
					"[TrackerServer] Tap packet received from ${tracker.name}: ${packet.tap}"
				)
			}

			is UDPPacket14Error -> {
				LogManager.severe(
					"[TrackerServer] Error received from ${received.socketAddress}: ${packet.errorNumber}"
				)
				tracker = connection?.getTracker(packet.sensorId)
				if (tracker == null) return
				tracker.status = TrackerStatus.ERROR
			}

			is UDPPacket15SensorInfo -> {
				if (connection == null) return
				setUpSensor(connection, packet.sensorId, packet.sensorType, packet.sensorStatus)
				// Send ack
				bb.limit(bb.capacity())
				bb.rewind()
				parser.writeSensorInfoResponse(bb, connection, packet)
				socket.send(DatagramPacket(rcvBuffer, bb.position(), connection.address))
				LogManager.info(
					"[TrackerServer] Sensor info for ${connection.descriptiveName}/${packet.sensorId}: ${packet.sensorStatus}"
				)
			}

			is UDPPacket19SignalStrength -> connection?.trackers?.values?.forEach {
				it.signalStrength = packet.signalStrength
			}

			is UDPPacket20Temperature -> {
				tracker = connection?.getTracker(packet.sensorId)
				if (tracker == null) return
				tracker.temperature = packet.temperature
			}

			is UDPPacket21UserAction -> {
				if (connection == null) return
				var name = ""
				when (packet.type) {
					UDPPacket21UserAction.RESET_FULL -> {
						name = "Full"
						VRServer.instance.resetHandler.sendStarted(ResetType.Full)
						VRServer.instance.resetTrackersFull(resetSourceName)
					}

					UDPPacket21UserAction.RESET_YAW -> {
						name = "Yaw"
						VRServer.instance.resetHandler.sendStarted(ResetType.Yaw)
						VRServer.instance.resetTrackersYaw(resetSourceName)
					}

					UDPPacket21UserAction.RESET_MOUNTING -> {
						name = "Mounting"
						VRServer
							.instance
							.resetHandler
							.sendStarted(ResetType.Mounting)
						VRServer.instance.resetTrackersMounting(resetSourceName)
					}
				}

				LogManager.info(
					"[TrackerServer] User action from ${connection.descriptiveName } received. $name reset performed."
				)
			}

			is UDPPacket22FeatureFlags -> {
				if (connection == null) return
				// Respond with server flags
				bb.limit(bb.capacity())
				bb.rewind()
				parser.write(bb, connection, packet)
				socket.send(DatagramPacket(rcvBuffer, bb.position(), connection.address))
				connection.firmwareFeatures = packet.firmwareFeatures
			}

			is UDPPacket200ProtocolChange -> {}
		}
	}

	companion object {
		/**
		 * Change between IMU axes and OpenGL/SteamVR axes
		 */
		private val AXES_OFFSET = fromRotationVector(-FastMath.HALF_PI, 0f, 0f)
		private const val resetSourceName = "TrackerServer"
		private fun packetToString(packet: DatagramPacket?): String {
			val sb = StringBuilder()
			sb.append("DatagramPacket{")
			if (packet == null) {
				sb.append("null")
			} else {
				sb.append(packet.address.toString())
				sb.append(packet.port)
				sb.append(',')
				sb.append(packet.length)
				sb.append(',')
				sb.append(ArrayUtils.toString(packet.data))
			}
			sb.append('}')
			return sb.toString()
		}
	}
}
