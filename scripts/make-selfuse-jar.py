#!/usr/bin/env python3
"""打「自用包」：把本机配置注入已打好的 jar，产出可直接跑的副本。

用法：
    python scripts/make-selfuse-jar.py target/fqxs-1.0.0.jar dist/fqxs-1.0.0-selfuse-YYYYMMDD.jar

做的事：只重写 jar 里的 BOOT-INF/classes/application.yml ——
  1. 登录口令回本机自用值（从 SELFUSE_USER / SELFUSE_PASSWORD 环境变量读，**不写死在本文件里**）；
  2. 三个内置引擎的 api-key 填成 data/ai-keys.properties 里的真值，
     仍保留 ${ENV:默认值} 形式，所以环境变量永远优先。

为什么不改仓库里的 application.yml：那份必须保持 admin/admin + 空 Key 才能公开
（见 doc/技术交接.md §4.4 / §4.4.1）。这里改的是 dist/ 下的产物，dist/ 已 gitignore。

边界：这个 jar 一旦转发就等于交出 Key，只自己用。
"""
import os
import pathlib
import re
import sys
import zipfile

TARGET_ENTRY = 'BOOT-INF/classes/application.yml'
KEYS_FILE = pathlib.Path(os.getenv('AI_KEYS_FILE', 'data/ai-keys.properties'))
ENGINES = [('glm', 'GLM_API_KEY'), ('qwen', 'QWEN_API_KEY'), ('qianfan', 'QIANFAN_API_KEY')]


def read_keys() -> dict:
    if not KEYS_FILE.exists():
        sys.exit(f'找不到密钥文件：{KEYS_FILE}')
    props = {}
    for line in KEYS_FILE.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        props[key.strip()] = value.strip()
    return props


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    src, dst = sys.argv[1], sys.argv[2]
    keys = read_keys()
    # 自用口令只从环境变量取，不写进本文件（本文件要进仓库）
    user = os.getenv('SELFUSE_USER')
    password = os.getenv('SELFUSE_PASSWORD')
    if not user or not password:
        sys.exit('请先设置 SELFUSE_USER / SELFUSE_PASSWORD 环境变量再运行（口令不写进仓库）')
    # 防呆：曾把 .bat 的赋值语句整体当成了值（SELFUSE_PASSWORD="APP_LOGIN_PASSWORD=xxx"），
    # 于是包内默认值多了 "APP_LOGIN_PASSWORD=" 前缀，服务器上输对口令也永远 401。
    # 前缀一旦进包，只能在包内看到，排查要 dump jar —— 在这里直接拦掉。
    for name, value in (('SELFUSE_USER', user), ('SELFUSE_PASSWORD', password)):
        if '=' in value or ' ' in value:
            sys.exit(f'{name} 只能是值本身，不能带「变量名=」前缀或空格：'
                     f'当前长度 {len(value)}。正确用法：set SELFUSE_PASSWORD=<你的口令>')

    with zipfile.ZipFile(src) as zin:
        yml = zin.read(TARGET_ENTRY).decode('utf-8')

        def sub(pattern: str, repl: str) -> None:
            nonlocal yml
            yml, n = re.subn(pattern, repl, yml, count=1)
            if n != 1:
                sys.exit(f'未命中（jar 里的 application.yml 结构与预期不符）：{pattern}')

        sub(r'username: \$\{APP_LOGIN_USER:[^}]*\}', 'username: ${APP_LOGIN_USER:%s}' % user)
        sub(r'password: \$\{APP_LOGIN_PASSWORD:[^}]*\}', 'password: ${APP_LOGIN_PASSWORD:%s}' % password)
        for prop, env in ENGINES:
            value = keys.get(prop, '')
            if not value:
                print(f'  跳过 {prop}：密钥文件里没有这一项')
                continue
            sub(r'%s-api-key: \$\{%s:[^}]*\}' % (prop, env),
                '%s-api-key: ${%s:%s}' % (prop, env, value))

        # 自用默认只拉男频：批量拉取与作者信息补齐都只走男频，第三方额度不摊给女频。
        # 只在键不存在时播种，已有库想改要在页面「拉取」里切一次。
        sub(r'default-fetch-gender: \$\{APP_FETCH_GENDER:[^}]*\}',
            'default-fetch-gender: ${APP_FETCH_GENDER:male}')

        patched = yml.encode('utf-8')
        with zipfile.ZipFile(dst, 'w') as zout:
            for item in zin.infolist():
                zout.writestr(item, patched if item.filename == TARGET_ENTRY else zin.read(item.filename))

    print(f'已生成 {dst}')
    print(f'  登录：{user} / {"*" * len(password)}')
    print('  拉取频道默认：male（只拉男频）')
    for prop, _ in ENGINES:
        value = keys.get(prop, '')
        print(f'  {prop}-api-key：{"…" + value[-4:] if value else "(空)"}')


if __name__ == '__main__':
    main()
