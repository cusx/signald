# signald - An (unofficial) Signal Daemon

signald is a daemon that facilitates communication over Signal.


## Quick Start
1. Run `./gradlew installDist` to build signald
1. Run `build/install/signald/bin/signald signald.sock` to start signald. It will continue running until killed (or ctrl-C)
1. In a second terminal window, connect to the signald control socket: `nc -U signald.sock`
1. Register a new number on signal by typing this: `{"type": "register", "username": "+12024561414"}` (replace `+12024561414` with your own number)
1. Once you receive the verification text, submit it like this: `{"type": "verify", "username": "+12024561414", "code": "000-000"}` where `000-000` is the verification code.
1. Incoming messages will be sent to the socket and shown on your screen. To send a message, use something like this:

```json
{
  "type": "send",
  "username": "+12024561414",
  "recipientNumber": "+14235290302",
  "messageBody": "Hello, Dave"
}
```

*However, it must all be sent on a single line* otherwise signald will attempt to interpret each line as json.


## Control Messages
Each message sent to the control socket must be valid JSON and have a `type` field. The possible message types and their
arguments are enumerated below. All messages may optionally include an `id` field. When signald follows up on a previous
command, it will include the same `id` value. Most commands (but not all) require `username` field, which is the number
to use for this action, as multiple numbers can be registered with signald at the same time.

### `send`
Sends a signal message to another user or a group. Possible values are:

| Field | Type | Required? | Description |
|-------|------|-----------|-------------|
| `username` | string | yes | The signal number you are sending *from*. |
| `recipientNumber` | string | no | The number you are sending to. Required if not sending to a group |
| `recipientGroupId` | string | no | The base64 encoded group ID to send to. Required if sending to a group |
| `messageBody` | string | no | The text of the message. |
| `attachmentFilenames` | list of strings | no | A list of files to attach, by path on the local disk. |

### `register`

Begins the process of registering a new number on signal for use with signald. Possible values are:
| Field | Type | Required? | Description |
|-------|------|-----------|-------------|
| `username` | string | yes | The phone number to register |
| `voice` | boolean | no | Indicates if the verification code should be sent via a phone call. If `false` or not set the verification is done via SMS |


### `verify`

Completes the registration process, by providing a verification code sent after the `register` command. Possible values are:

| Field | Type | Required? | Description |
|-------|------|-----------|-------------|
| `username` | string | yes | The phone number that is being verified |
| `code` | string | yes | The verification code. The `-` in the middle code is optional.


### `addDevice`

Adds another device to a signal account that signald controls the master device on. Possible values are:
| Field | Type | Required? | Description |
|-------|------|-----------|-------------|
| `username` | string | yes | The account to add the device to. |
| `uri` | string | yes | The `tsdevice:` URI that is provided by the other device (displayed as a QR code normally) |




### `list_accounts`
Returns a list of all currently known accounts in signald, including ones that have not completed registration. No other fields are used.


## Contributing
I would like to get this to the point that anything one can do in the signal app can also be done via signald. There should be open issues for all missing features. If you have a feature you want feel free to work on it and submit a pull request. If you don't want to work on it, follow the relevant issue and get notified when there is progress.
