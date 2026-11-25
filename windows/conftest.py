import pytest


def pytest_addoption(parser):
    parser.addoption("--integration", action="store_true", help="run network tests")


def pytest_collection_modifyitems(config, items):
    if config.getoption("--integration"):
        return
    skip = pytest.mark.skip(reason="needs --integration")
    for item in items:
        if "integration" in item.keywords:
            item.add_marker(skip)
