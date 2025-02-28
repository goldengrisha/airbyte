# Copyright (c) 2023 Airbyte, Inc., all rights reserved.

"""
The AirbyteEntrypoint is important because it is a service layer that orchestrate how we execute commands from the
[common interface](https://docs.airbyte.com/understanding-airbyte/airbyte-protocol#common-interface) through the source Python
implementation. There is some logic about which message we send to the platform and when which is relevant for integration testing. Other
than that, there are integrations point that are annoying to integrate with using Python code:
* Sources communicate with the platform using stdout. The implication is that the source could just print every message instead of
    returning things to source.<method> or to using the message repository. WARNING: As part of integration testing, we will not support
    messages that are simply printed. The reason is that capturing stdout relies on overriding sys.stdout (see
    https://docs.python.org/3/library/contextlib.html#contextlib.redirect_stdout) which clashes with how pytest captures logs and brings
    considerations for multithreaded applications. If code you work with uses `print` statements, please migrate to
    source.message_repository to emit those messages
* The entrypoint interface relies on file being written on the file system
"""

import json
import logging
import tempfile
from io import StringIO
from pathlib import Path
from typing import Any, List, Mapping, Optional, Union

from airbyte_cdk.entrypoint import AirbyteEntrypoint
from airbyte_cdk.logger import AirbyteLogFormatter
from airbyte_cdk.sources import Source
from airbyte_protocol.models import AirbyteLogMessage, AirbyteMessage, ConfiguredAirbyteCatalog, Level, TraceType, Type
from pydantic.error_wrappers import ValidationError


class EntrypointOutput:
    def __init__(self, messages: List[str]):
        try:
            self._messages = [self._parse_message(message) for message in messages]
        except ValidationError as exception:
            raise ValueError("All messages are expected to be AirbyteMessage") from exception

    @staticmethod
    def _parse_message(message: str) -> AirbyteMessage:
        try:
            return AirbyteMessage.parse_obj(json.loads(message))
        except (json.JSONDecodeError, ValidationError):
            # The platform assumes that logs that are not of AirbyteMessage format are log messages
            return AirbyteMessage(type=Type.LOG, log=AirbyteLogMessage(level=Level.INFO, message=message))

    @property
    def records_and_state_messages(self) -> List[AirbyteMessage]:
        return self._get_message_by_types([Type.RECORD, Type.STATE])

    @property
    def records(self) -> List[AirbyteMessage]:
        return self._get_message_by_types([Type.RECORD])

    @property
    def state_messages(self) -> List[AirbyteMessage]:
        return self._get_message_by_types([Type.STATE])

    @property
    def logs(self) -> List[AirbyteMessage]:
        return self._get_message_by_types([Type.LOG])

    @property
    def trace_messages(self) -> List[AirbyteMessage]:
        return self._get_message_by_types([Type.TRACE])

    @property
    def analytics_messages(self) -> List[AirbyteMessage]:
        return [message for message in self._get_message_by_types([Type.TRACE]) if message.trace.type == TraceType.ANALYTICS]

    def _get_message_by_types(self, message_types: List[Type]) -> List[AirbyteMessage]:
        return [message for message in self._messages if message.type in message_types]


def read(source: Source, config: Mapping[str, Any], catalog: ConfiguredAirbyteCatalog, state: Optional[Any] = None) -> EntrypointOutput:
    """
    config and state must be json serializable
    """
    log_capture_buffer = StringIO()
    stream_handler = logging.StreamHandler(log_capture_buffer)
    stream_handler.setLevel(logging.INFO)
    stream_handler.setFormatter(AirbyteLogFormatter())
    parent_logger = logging.getLogger("")
    parent_logger.addHandler(stream_handler)

    with tempfile.TemporaryDirectory() as tmp_directory:
        tmp_directory_path = Path(tmp_directory)
        args = [
            "read",
            "--config",
            make_file(tmp_directory_path / "config.json", config),
            "--catalog",
            make_file(tmp_directory_path / "catalog.json", catalog.json()),
        ]
        if state:
            args.extend(
                [
                    "--state",
                    make_file(tmp_directory_path / "state.json", state),
                ]
            )
        source_entrypoint = AirbyteEntrypoint(source)
        parsed_args = source_entrypoint.parse_args(args)
        messages = list(source_entrypoint.run(parsed_args))
        captured_logs = log_capture_buffer.getvalue().split("\n")[:-1]

        parent_logger.removeHandler(stream_handler)

        return EntrypointOutput(messages + captured_logs)


def make_file(path: Path, file_contents: Optional[Union[str, Mapping[str, Any], List[Mapping[str, Any]]]]) -> str:
    if isinstance(file_contents, str):
        path.write_text(file_contents)
    else:
        path.write_text(json.dumps(file_contents))
    return str(path)
