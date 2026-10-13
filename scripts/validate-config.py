#!/usr/bin/env python3
"""Validate the manually maintained TV configuration without network access.

Usage: python scripts/validate-config.py [path/to/tv-config.json]
The default path is config/tv-config.json relative to this script's project.
Exit status: 0 for a valid file, 1 for invalid data or an unreadable file.
Only field locations and aggregate counts are printed, never source values.
"""

import argparse
import json
from pathlib import Path
import sys
from urllib.parse import urlsplit


CHANNEL_UPDATE_HOURS = {2, 6, 24, 72, 120, 240, 720}
MAX_FILE_BYTES = 8 * 1024 * 1024


class ConfigError(ValueError):
    """A configuration error safe to display without exposing field values."""


def fail(field, message):
    raise ConfigError("{}: {}".format(field, message))


def require_object(value, field):
    if not isinstance(value, dict):
        fail(field, "必须是 JSON 对象")
    return value


def require_string(value, field, nonempty=False):
    if not isinstance(value, str):
        fail(field, "必须是字符串")
    if nonempty and not value.strip():
        fail(field, "不能为空或只有空白")
    return value


def require_bool(value, field):
    if type(value) is not bool:
        fail(field, "必须是 true 或 false")


def require_integer(value, field):
    # bool is a subclass of int in Python; reject it explicitly.
    if type(value) is not int:
        fail(field, "必须是整数")
    return value


def require_url(value, field, allow_empty=False):
    value = require_string(value, field)
    if value == "" and allow_empty:
        return
    if not value or any(ord(character) <= 32 or ord(character) == 127
                        for character in value):
        fail(field, "必须是完整的 HTTP(S) 地址，不能含空白或控制字符")
    try:
        parsed = urlsplit(value)
        hostname = parsed.hostname
        # Accessing port also rejects malformed ports and out-of-range values.
        parsed.port
        valid = parsed.scheme.lower() in {"http", "https"} and bool(hostname)
    except (ValueError, UnicodeError):
        valid = False
    if not valid:
        fail(field, "必须是完整的 HTTP(S) 地址")


def reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ConfigError("JSON 对象中存在重复字段")
        result[key] = value
    return result


def reject_non_json_number(_value):
    raise ConfigError("JSON 不允许 NaN 或 Infinity")


def read_config(path):
    try:
        with path.open("rb") as config_file:
            raw = config_file.read(MAX_FILE_BYTES + 1)
    except OSError:
        raise ConfigError("无法读取配置文件，请检查路径和读取权限") from None
    if len(raw) > MAX_FILE_BYTES:
        raise ConfigError("配置文件超过 8 MiB")
    try:
        source = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        raise ConfigError("配置文件必须使用有效的 UTF-8 编码") from None
    if source.startswith("\ufeff"):
        raise ConfigError("请使用不含 BOM 的 UTF-8 编码保存配置文件")
    try:
        return json.loads(source, object_pairs_hook=reject_duplicate_keys,
                          parse_constant=reject_non_json_number)
    except json.JSONDecodeError as error:
        raise ConfigError("JSON 语法错误（第 {} 行，第 {} 列）".format(
            error.lineno, error.colno)) from None
    except (RecursionError, OverflowError):
        raise ConfigError("JSON 层级或数字超出可解析范围") from None


def validate_config(config):
    config = require_object(config, "配置")
    if require_integer(config.get("version"), "version") != 1:
        fail("version", "当前只支持版本 1")
    require_url(config.get("channelSourceUrl"), "channelSourceUrl", allow_empty=True)
    interval = require_integer(config.get("channelUpdateHours"), "channelUpdateHours")
    if interval not in CHANNEL_UPDATE_HOURS:
        fail("channelUpdateHours", "必须是 2、6、24、72、120、240 或 720")

    epg = require_object(config.get("epg"), "epg")
    require_bool(epg.get("enabled"), "epg.enabled")
    require_url(epg.get("url"), "epg.url", allow_empty=True)
    epg_interval = require_integer(epg.get("refreshHours"), "epg.refreshHours")
    if not 1 <= epg_interval <= 720:
        fail("epg.refreshHours", "必须介于 1 与 720 之间")

    playback = require_object(config.get("playback"), "playback")
    for key in ("fastSwitch", "rememberSource"):
        require_bool(playback.get(key), "playback." + key)
    source_timeout = require_integer(playback.get("sourceTimeoutSeconds", 15), "playback.sourceTimeoutSeconds")
    if source_timeout not in (0, 5, 10, 15, 30):
        fail("playback.sourceTimeoutSeconds", "必须是 0、5、10、15 或 30；0 表示关闭")

    channels = config.get("channels")
    if not isinstance(channels, list) or not 1 <= len(channels) <= 5000:
        fail("channels", "必须是包含 1 至 5000 个频道的数组")
    source_count = 0
    mapped_count = 0
    for channel_index, channel in enumerate(channels):
        field = "channels[{}]".format(channel_index)
        channel = require_object(channel, field)
        require_string(channel.get("name"), field + ".name", nonempty=True)
        require_string(channel.get("group"), field + ".group", nonempty=True)
        for key in ("id", "epgId"):
            if key in channel:
                require_string(channel[key], field + "." + key)
        if channel.get("epgId", "").strip():
            mapped_count += 1
        sources = channel.get("urls")
        if not isinstance(sources, list) or not 1 <= len(sources) <= 32:
            fail(field + ".urls", "必须是包含 1 至 32 个来源的数组")
        for source_index, source in enumerate(sources):
            source_field = "{}.urls[{}]".format(field, source_index)
            # The Android importer accepts both bare URLs and source objects.
            if isinstance(source, str):
                require_url(source, source_field)
            else:
                source = require_object(source, source_field)
                require_url(source.get("url"), source_field + ".url")
                if "extra" in source:
                    require_string(source["extra"], source_field + ".extra")
            source_count += 1
    # Repeated channel names in different groups are intentional and valid.
    return len(channels), source_count, mapped_count


def main(argv=None):
    parser = argparse.ArgumentParser(description="验证频道配置 JSON，不访问网络。")
    parser.add_argument("path", nargs="?", type=Path,
                        default=Path(__file__).resolve().parents[1] / "config" / "tv-config.json",
                        help="配置文件路径；默认使用项目 config/tv-config.json")
    args = parser.parse_args(argv)
    try:
        channels, sources, mapped = validate_config(read_config(args.path))
    except ConfigError as error:
        print("配置无效：{}".format(error), file=sys.stderr)
        return 1
    print("配置有效：{} 个频道，{} 个播放来源，{} 个 EPG 映射。".format(
        channels, sources, mapped))
    return 0


if __name__ == "__main__":
    sys.exit(main())
