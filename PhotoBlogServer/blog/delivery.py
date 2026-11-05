"""Deterministic payload identity for an edge client's durable outbox."""
import hashlib
import json


def payload_digest(validated_data):
    # Hash the actual bytes, not the client-controlled filename or MIME header.
    image = validated_data['image']
    position = image.tell()
    image_hash = hashlib.sha256()
    try:
        image.seek(0)
        for chunk in image.chunks():
            image_hash.update(chunk)
    finally:
        image.seek(position)
    published = validated_data.get('published_date')
    fields = {
        'title': validated_data['title'],
        'text': validated_data['text'],
        'published_date': published.isoformat() if published else None,
        'image_sha256': image_hash.hexdigest(),
    }
    canonical = json.dumps(fields, sort_keys=True, ensure_ascii=False,
                           separators=(',', ':')).encode('utf-8')
    return hashlib.sha256(canonical).hexdigest()
