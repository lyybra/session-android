@file:JvmName("MultiDeviceUtilities")
package org.thoughtcrime.securesms.loki

import android.content.Context
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.all
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import nl.komponents.kovenant.toFailVoid
import nl.komponents.kovenant.ui.successUi
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil
import org.thoughtcrime.securesms.database.Address
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.logging.Log
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.loki.api.DeviceLink
import org.whispersystems.signalservice.loki.api.LokiDeviceLinkUtilities
import org.whispersystems.signalservice.loki.api.LokiFileServerAPI
import org.whispersystems.signalservice.loki.messaging.LokiThreadFriendRequestStatus
import org.whispersystems.signalservice.loki.utilities.recover
import org.whispersystems.signalservice.loki.utilities.retryIfNeeded
import java.util.*
import kotlin.concurrent.schedule

fun checkForRevocation(context: Context) {
  val primaryDevice = TextSecurePreferences.getMasterHexEncodedPublicKey(context) ?: return
  val ourDevice = TextSecurePreferences.getLocalNumber(context)

  LokiFileServerAPI.shared.getDeviceLinks(primaryDevice, true).bind { mappings ->
    val ourMapping = mappings.find { it.slaveHexEncodedPublicKey == ourDevice }
    if (ourMapping != null) throw Error("Device has not been revoked")
    // remove pairing authorisations for our device
    DatabaseFactory.getLokiAPIDatabase(context).clearDeviceLinks(ourDevice)
    LokiFileServerAPI.shared.updateUserDeviceLinks()
  }.successUi {
    TextSecurePreferences.setNeedsRevocationCheck(context, false)
    ApplicationContext.getInstance(context).clearData()
  }.fail { error ->
    TextSecurePreferences.setNeedsRevocationCheck(context, true)
    Log.d("Loki", "Revocation check failed: ${error.message ?: error}")
  }
}

fun getAllDeviceFriendRequestStatuses(context: Context, hexEncodedPublicKey: String): Promise<Map<String, LokiThreadFriendRequestStatus>, Exception> {
  val lokiThreadDatabase = DatabaseFactory.getLokiThreadDatabase(context)
  return LokiDeviceLinkUtilities.getAllLinkedDeviceHexEncodedPublicKeys(hexEncodedPublicKey).map { keys ->
    val map = mutableMapOf<String, LokiThreadFriendRequestStatus>()
    for (devicePublicKey in keys) {
      val device = Recipient.from(context, Address.fromSerialized(devicePublicKey), false)
      val threadID = DatabaseFactory.getThreadDatabase(context).getThreadIdIfExistsFor(device)
      val friendRequestStatus = if (threadID < 0) LokiThreadFriendRequestStatus.NONE else lokiThreadDatabase.getFriendRequestStatus(threadID)
      map[devicePublicKey] = friendRequestStatus
    }
    map
  }.recover { mutableMapOf() }
}

fun getAllDevicePublicKeysWithFriendStatus(context: Context, hexEncodedPublicKey: String): Promise<Map<String, Boolean>, Unit> {
  val userHexEncodedPublicKey = TextSecurePreferences.getLocalNumber(context)
  return LokiDeviceLinkUtilities.getAllLinkedDeviceHexEncodedPublicKeys(hexEncodedPublicKey).map { keys ->
    val devices = keys.toMutableSet()
    if (hexEncodedPublicKey != userHexEncodedPublicKey) {
      devices.remove(userHexEncodedPublicKey)
    }
    val friends = getFriendPublicKeys(context, devices)
    val friendMap = mutableMapOf<String, Boolean>()
    for (device in devices) {
      friendMap[device] = friends.contains(device)
    }
    friendMap
  }.toFailVoid()
}

fun getFriendCount(context: Context, devices: Set<String>): Int {
  return getFriendPublicKeys(context, devices).count()
}

fun shouldAutomaticallyBecomeFriendsWithDevice(publicKey: String, context: Context): Promise<Boolean, Exception> {
  // Don't become friends if we're a group
  if (!Address.fromSerialized(publicKey).isPhone) {
    return Promise.of(false)
  }

  // If this public key is our primary device then we should become friends
  if (publicKey == TextSecurePreferences.getMasterHexEncodedPublicKey(context)) {
    return Promise.of(true)
  }

  return LokiDeviceLinkUtilities.getMasterHexEncodedPublicKey(publicKey).bind { primaryDevicePublicKey ->
    // If the public key doesn't have any other devices then go through regular friend request logic
    if (primaryDevicePublicKey == null) {
      return@bind Promise.of(false)
    }

    // If the primary device public key matches our primary device then we should become friends since this is our other device
    if (primaryDevicePublicKey == TextSecurePreferences.getMasterHexEncodedPublicKey(context)) {
      return@bind Promise.of(true)
    }

    // If we are friends with any of the other devices then we should become friends
    isFriendsWithAnyLinkedDevice(context, Address.fromSerialized(primaryDevicePublicKey))
  }
}

fun sendPairingAuthorisationMessage(context: Context, contactHexEncodedPublicKey: String, authorisation: DeviceLink): Promise<Unit, Exception> {
  val messageSender = ApplicationContext.getInstance(context).communicationModule.provideSignalMessageSender()
  val address = SignalServiceAddress(contactHexEncodedPublicKey)
  val message = SignalServiceDataMessage.newBuilder().withPairingAuthorisation(authorisation)
  // A REQUEST should always act as a friend request. A GRANT should always be replying back as a normal message.
  if (authorisation.type == DeviceLink.Type.REQUEST) {
    val preKeyBundle = DatabaseFactory.getLokiPreKeyBundleDatabase(context).generatePreKeyBundle(address.number)
    message.asFriendRequest(true).withPreKeyBundle(preKeyBundle)
  } else {
    // Send over our profile key so that our linked device can get our profile picture
    message.withProfileKey(ProfileKeyUtil.getProfileKey(context))
  }

  return try {
    Log.d("Loki", "Sending authorisation message to: $contactHexEncodedPublicKey.")
    val udAccess = UnidentifiedAccessUtil.getAccessFor(context, Recipient.from(context, Address.fromSerialized(contactHexEncodedPublicKey), false))
    val result = messageSender.sendMessage(0, address, udAccess, message.build())
    if (result.success == null) {
      val exception = when {
        result.isNetworkFailure -> "Failed to send authorisation message due to a network error."
        else -> "Failed to send authorisation message."
      }
      throw Exception(exception)
    }
    Promise.ofSuccess(Unit)
  } catch (e: Exception) {
    Log.d("Loki", "Failed to send authorisation message to: $contactHexEncodedPublicKey.")
    Promise.ofFail(e)
  }
}

fun signAndSendPairingAuthorisationMessage(context: Context, pairingAuthorisation: DeviceLink) {
  val userPrivateKey = IdentityKeyUtil.getIdentityKeyPair(context).privateKey.serialize()
  val signedPairingAuthorisation = pairingAuthorisation.sign(DeviceLink.Type.AUTHORIZATION, userPrivateKey)
  if (signedPairingAuthorisation == null || signedPairingAuthorisation.type != DeviceLink.Type.AUTHORIZATION) {
    Log.d("Loki", "Failed to sign pairing authorization.")
    return
  }
  DatabaseFactory.getLokiAPIDatabase(context).addDeviceLink(signedPairingAuthorisation)
  TextSecurePreferences.setMultiDevice(context, true)

  val address = Address.fromSerialized(pairingAuthorisation.slaveHexEncodedPublicKey)

  val sendPromise = retryIfNeeded(8) {
    sendPairingAuthorisationMessage(context, address.serialize(), signedPairingAuthorisation)
  }.fail {
    Log.d("Loki", "Failed to send pairing authorization message to ${address.serialize()}.")
  }

  val updatePromise = LokiFileServerAPI.shared.updateUserDeviceLinks().fail {
    Log.d("Loki", "Failed to update device mapping")
  }

  // If both promises complete successfully then we should sync our contacts
  all(listOf(sendPromise, updatePromise), cancelOthersOnError = false).success {
    Log.d("Loki", "Successfully pairing with a secondary device! Syncing contacts.")
    // Send out sync contact after a delay
    Timer().schedule(3000) {
      MessageSender.syncAllContacts(context, address)
    }
  }
}

fun isOneOfOurDevices(context: Context, address: Address): Promise<Boolean, Exception> {
  if (address.isGroup || address.isEmail || address.isMmsGroup) {
    return Promise.of(false)
  }

  val ourPublicKey = TextSecurePreferences.getLocalNumber(context)
  return LokiDeviceLinkUtilities.getAllLinkedDeviceHexEncodedPublicKeys(ourPublicKey).map { devices ->
    devices.contains(address.serialize())
  }
}

fun isFriendsWithAnyLinkedDevice(context: Context, recipient: Recipient): Promise<Boolean, Exception> {
  return isFriendsWithAnyLinkedDevice(context, recipient.address)
}

fun isFriendsWithAnyLinkedDevice(context: Context, address: Address): Promise<Boolean, Exception> {
  if (!address.isPhone) { return Promise.of(true) }

  return getAllDeviceFriendRequestStatuses(context, address.serialize()).map { map ->
    for (status in map.values) {
      if (status == LokiThreadFriendRequestStatus.FRIENDS) {
        return@map true
      }
    }
    false
  }
}

fun hasPendingFriendRequestWithAnyLinkedDevice(context: Context, recipient: Recipient): Promise<Boolean, Exception> {
  if (recipient.isGroupRecipient) { return Promise.of(false) }

  return getAllDeviceFriendRequestStatuses(context, recipient.address.serialize()).map { map ->
    for (status in map.values) {
      if (status == LokiThreadFriendRequestStatus.REQUEST_SENDING || status == LokiThreadFriendRequestStatus.REQUEST_SENT || status == LokiThreadFriendRequestStatus.REQUEST_RECEIVED) {
        return@map true
      }
    }
    false
  }
}
