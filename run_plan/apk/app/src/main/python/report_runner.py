# -*- coding: utf-8 -*-
"""
Тонкая Android-обвязка над build_report.py (десктопный генератор отчёта, скопирован сюда БЕЗ
ИЗМЕНЕНИЙ -- см. d:\\Git\\mytest\\garmin\\run_plan\\build_report.py, синхронизировать вручную
при обновлении десктопной версии). Сам build_report.py про Android ничего не знает и не должен --
здесь только то, что нужно ИМЕННО для запуска под Chaquopy на телефоне:
  - MPLCONFIGDIR должен указывать в писабельную папку (обычно context.cacheDir/mplconfig)
    ДО первого `import matplotlib` -- в песочнице приложения $HOME недоступен на запись, и
    matplotlib иначе на некоторых устройствах падает при инициализации кэша шрифтов.
"""

import os


def run(db_path, out_path, json_path=None, mpl_config_dir=None):
    if mpl_config_dir:
        os.environ.setdefault("MPLCONFIGDIR", mpl_config_dir)
    import build_report
    build_report.main(db_path, out_path, json_path)
