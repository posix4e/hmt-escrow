"""Run only in the dedicated CVAT container, via `manage.py shell`.

Creates a fresh local development account; never resets an existing password.
Redirect stdout to a mode-0600 file. It contains credentials, not public logs.
"""
import json
import secrets

from django.contrib.auth import get_user_model
from rest_framework.authtoken.models import Token

username = "hpb-owner"
if get_user_model().objects.filter(username=username).exists():
    raise RuntimeError("hpb-owner already exists; use its saved credentials")
password = secrets.token_urlsafe(32)
user = get_user_model().objects.create_superuser(
    username=username, email="hpb-owner@localhost.invalid", password=password
)
token = Token.objects.create(user=user)
print(json.dumps({"username": username, "password": password, "token": token.key}))
