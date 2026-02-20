# Smart Reply
Automatically check notifications, open the messaging app, read the chat, and draft + send a reply.

## Trigger
smart reply

## Steps
1. Check recent notifications for messaging apps using `notification.listen` (query with since_minutes=5)
2. Open the messaging app with the most recent notification using `messaging.open`
3. Read the current chat using `messaging.read` to understand context
4. Use vision analysis via `screen.capture` + `vision.analyze` if the chat contains images
5. Draft a contextual reply based on the conversation
6. Send the reply using `messaging.reply` (requires user approval)

## Tools
- notification.listen
- messaging.open
- messaging.read
- messaging.reply
- screen.capture
- vision.analyze
