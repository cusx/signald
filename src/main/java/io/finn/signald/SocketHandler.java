/**
 * Copyright (C) 2018 Finn Herzfeld
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.finn.signald;

import org.whispersystems.signalservice.api.push.exceptions.EncapsulatedExceptions;
import org.whispersystems.libsignal.InvalidKeyException;

import org.asamk.signal.AttachmentInvalidException;
import org.asamk.signal.UserAlreadyExists;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URISyntaxException;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonGenerator;

public class SocketHandler implements Runnable {
  private BufferedReader reader;
  private PrintWriter writer;
  private ConcurrentHashMap<String,Manager> managers;
  private ObjectMapper mpr = new ObjectMapper();


  public SocketHandler(Socket socket, ConcurrentHashMap<String,Manager> managers) throws IOException {
    this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    this.writer = new PrintWriter(socket.getOutputStream(), true);
    this.managers = managers;

    this.mpr.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY); // disable autodetect
    this.mpr.enable(SerializationFeature.WRITE_NULL_MAP_VALUES);
    this.mpr.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    this.mpr.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
  }

  public void run() {
    while(true) {
      String line = null;
      JsonRequest request;
      try {
        line = this.reader.readLine();
      } catch(IOException e) {
        System.err.println("Error parsing input:");
        e.printStackTrace();
        break;
      }
      if(line != null && !line.equals("")) {
        try {
          System.out.println(line);
          request = this.mpr.readValue(line, JsonRequest.class);
          handleRequest(request);
        } catch(Exception e) {
          e.printStackTrace();
          System.err.println("+-- Original request text: " + line);
        }
      }
    }
  }

  private void handleRequest(JsonRequest request) throws Exception {
    switch(request.type) {
      case "send":
        send(request);
        break;
      case "list_accounts":
        listAccounts(request);
        break;
      case "register":
        register(request);
        break;
      case "verify":
        verify(request);
        break;
      case "link":
        link(request);
        break;
      case "add_device":
        addDevice(request);
        break;
      default:
        System.err.println("Unknown command type " + request.type);
        break;
    }
  }

  private void send(JsonRequest request) {
    Manager manager = this.managers.get(request.username);
    try {
      manager.sendMessage(request.messageBody, request.attachmentFilenames, request.recipientNumber);
    } catch(EncapsulatedExceptions | AttachmentInvalidException | IOException e) {
      e.printStackTrace();
    }
  }

  private void listAccounts(JsonRequest request) throws JsonProcessingException {
    JsonAccountList accounts = new JsonAccountList(this.managers);
    this.reply("account_list", accounts, request.id);
  }

  private void register(JsonRequest request) throws IOException {
    System.err.println("Register request: " + request);
    Manager m = getManager(request.username);
    Boolean voice = false;
    if(request.voice != null) {
      voice = request.voice;
    }

    if(!m.userHasKeys()) {
      System.out.println("User has no keys, making some");
      m.createNewIdentity();
    }
    System.out.println("Registering (voice: " + voice + ")");
    m.register(voice);
    this.reply("verification_required", new JsonAccount(m), request.id);
  }

  private void verify(JsonRequest request) throws IOException {
    Manager m = getManager(request.username);
    if(!m.userHasKeys()) {
      System.err.println("User has no keys, first call register.");
    } else if(m.isRegistered()) {
      System.err.println("User is already verified");
    } else {
      System.err.println("Submitting verification code " + request.code + " for number " + request.username);
      m.verifyAccount(request.code);
      this.reply("verification_succeeded", new JsonAccount(m), request.id);
    }
  }

  private void addDevice(JsonRequest request) throws IOException, InvalidKeyException, AssertionError, URISyntaxException {
    Manager m = getManager(request.username);
    m.addDeviceLink(new URI(request.uri));
    reply("device_added", new JsonStatusMessage(4, "Successfully linked device", false), request.id);
  }

  private Manager getManager(String username) throws IOException {
    // So many problems in this method, need to have a single place to create new managers, probably in MessageReceiver
    String settingsPath = System.getProperty("user.home") + "/.config/signal";  // TODO: Stop hard coding this everywhere

    if(this.managers.containsKey(username)) {
      return this.managers.get(username);
    } else {
      Manager m = new Manager(username, settingsPath);
      if(m.userExists()) {
        m.init();
      }
      this.managers.put(username, m);
      return m;
    }
  }

  private void reply(String type, Object data, String id) throws JsonProcessingException {
    JsonMessageWrapper message = new JsonMessageWrapper(type, data, id);
    String jsonmessage = this.mpr.writeValueAsString(message);
    PrintWriter out = new PrintWriter(this.writer, true);
    out.println(jsonmessage);
  }

  private void link(JsonRequest request) throws AssertionError, IOException, InvalidKeyException {
    String settingsPath = System.getProperty("user.home") + "/.config/signal";  // TODO: Stop hard coding this everywhere
    Manager m = new Manager(null, settingsPath);
    m.createNewIdentity();
    String deviceName = "signald"; // TODO: Set this to "signald on <hostname>"
    if(request.deviceName != null) {
      deviceName = request.deviceName;
    }
    try {
      m.getDeviceLinkUri();
      this.reply("linking_uri", new JsonLinkingURI(m), request.id);
      m.finishDeviceLink(deviceName);
    } catch(TimeoutException e) {
      this.reply("linking_error", new JsonStatusMessage(1, "Timed out while waiting for device to link", true), request.id);
    } catch(IOException e) {
      this.reply("linking_error", new JsonStatusMessage(2, e.getMessage(), true), request.id);
    } catch(UserAlreadyExists e) {
      this.reply("linking_error", new JsonStatusMessage(3, "The user " + e.getUsername() + " already exists. Delete \"" + e.getFileName() + "\" and trying again.", true), request.id);
    }
  }
}
