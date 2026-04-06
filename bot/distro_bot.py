#!/usr/bin/env python3
"""
CheburMail Distribution Bot (@my_fabrica_bot, временно).

Фичи:
- Inline-кнопки для навигации
- Рассылка APK подписчикам
- Логирование входящих сообщений (для ответа через Claude)
- Админ-команды для управления

Входящие сообщения логируются в /tmp/cheburmail-inbox.jsonl
Claude может отвечать через: curl sendMessage
"""

import asyncio
import json
import logging
import os
import sys
from datetime import datetime
from pathlib import Path

from aiogram import Bot, Dispatcher, types, F
from aiogram.client.session.aiohttp import AiohttpSession
from aiogram.enums import ParseMode
from aiogram.filters import Command
from aiogram.types import (
    FSInputFile,
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    CallbackQuery,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler("/tmp/cheburmail-distro-bot.log"),
    ],
)
log = logging.getLogger("distro-bot")

# --- Config ---

BOT_TOKEN = "***REDACTED_BOT_TOKEN***"

APK_DEBUG = Path("/home/q/cheburmail/app/build/outputs/apk/debug/app-debug.apk")
APK_RELEASE = Path("/home/q/cheburmail/app/build/outputs/apk/release/app-release.apk")

ADMIN_IDS = {***REDACTED_CHAT_ID***}  # Eugene
BETA_IDS = {***REDACTED_CHAT_ID***, ***REDACTED_CHAT_ID***}  # Eugene, Marina

SUBSCRIBERS_FILE = Path(__file__).parent / "subscribers.json"
INBOX_FILE = Path("/tmp/cheburmail-inbox.jsonl")

PROXY = os.getenv("TELEGRAM_PROXY", "http://127.0.0.1:7897")

# --- Тексты ---

WELCOME_TEXT = """🔐 <b>CheburMail</b> — защищённый мессенджер

Сквозное шифрование поверх обычной почты.
Провайдер видит только метаданные, но не содержимое."""

ABOUT_TEXT = """🔐 <b>CheburMail</b>

E2E зашифрованный мессенджер для Android поверх Yandex Mail / Mail.ru.

<b>Шифрование:</b> X25519 + XSalsa20-Poly1305 (libsodium)
<b>Транспорт:</b> IMAP/SMTP (обычная почта)
<b>Результат:</b> Провайдер видит кто пишет кому, но не что

<b>Возможности:</b>
• Текстовые сообщения с E2E шифрованием
• 📸 Фото (камера и галерея)
• 📎 Файлы любого типа
• 🎙 Голосовые сообщения
• 🔑 Обмен ключами через QR-код
• 🔄 Pull-to-refresh синхронизация

⚠️ <i>Бета-версия. Обратная связь приветствуется!</i>"""

INSTALL_TEXT = """📲 <b>Инструкция по установке</b>

1. Скачайте APK кнопкой ниже
2. Откройте скачанный файл
3. Разрешите установку из неизвестных источников
4. Установите CheburMail
5. Настройте почтовый аккаунт (Yandex или Mail.ru)
6. Сгенерируйте ключи шифрования
7. Обменяйтесь QR-кодами с собеседником

<b>⚠️ Xiaomi/MIUI/HyperOS:</b>
Если установка блокируется:
Настройки → Защита → Специальный доступ → Установка неизвестных приложений"""

# --- Кнопки ---

def main_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="📲 Скачать APK", callback_data="get_apk")],
        [
            InlineKeyboardButton(text="ℹ️ О приложении", callback_data="about"),
            InlineKeyboardButton(text="📋 Установка", callback_data="install"),
        ],
        [InlineKeyboardButton(text="💬 Написать разработчику", callback_data="feedback")],
    ])


def back_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="⬅️ Назад", callback_data="main_menu")],
    ])


def apk_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="📲 Скачать APK", callback_data="get_apk")],
        [InlineKeyboardButton(text="⬅️ Назад", callback_data="main_menu")],
    ])


# --- Subscribers DB ---

def load_subscribers() -> dict:
    if SUBSCRIBERS_FILE.exists():
        try:
            return json.loads(SUBSCRIBERS_FILE.read_text())
        except Exception:
            return {}
    return {}


def save_subscribers(subs: dict):
    SUBSCRIBERS_FILE.write_text(json.dumps(subs, ensure_ascii=False, indent=2))


def add_subscriber(user: types.User) -> bool:
    subs = load_subscribers()
    key = str(user.id)
    is_new = key not in subs
    subs[key] = {
        "name": user.full_name,
        "username": user.username,
        "joined": datetime.now().isoformat() if is_new else subs.get(key, {}).get("joined"),
        "beta": user.id in BETA_IDS,
    }
    save_subscribers(subs)
    return is_new


# --- Helpers ---

def get_stable_apk() -> Path | None:
    """Отдаём самый свежий APK — release или debug."""
    release_ok = APK_RELEASE.exists()
    debug_ok = APK_DEBUG.exists()
    if release_ok and debug_ok:
        # Берём более свежий
        if APK_RELEASE.stat().st_mtime >= APK_DEBUG.stat().st_mtime:
            return APK_RELEASE
        return APK_DEBUG
    if release_ok:
        return APK_RELEASE
    if debug_ok:
        return APK_DEBUG
    return None


def apk_info(path: Path) -> str:
    size_mb = path.stat().st_size / (1024 * 1024)
    mtime = datetime.fromtimestamp(path.stat().st_mtime)
    return f"📦 {size_mb:.1f} MB | Собран: {mtime:%d.%m.%Y %H:%M}"


def log_incoming(user: types.User, text: str):
    """Логируем входящие сообщения для ответа через Claude."""
    entry = {
        "ts": datetime.now().isoformat(),
        "chat_id": user.id,
        "name": user.full_name,
        "username": user.username,
        "text": text,
    }
    with open(INBOX_FILE, "a") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


async def send_apk(bot: Bot, chat_id: int, apk_path: Path, caption: str = "") -> bool:
    try:
        info = apk_info(apk_path)
        full_caption = f"{caption}\n{info}" if caption else info
        await bot.send_document(
            chat_id=chat_id,
            document=FSInputFile(apk_path, filename="CheburMail.apk"),
            caption=full_caption,
            parse_mode=ParseMode.HTML,
        )
        return True
    except Exception as e:
        log.error(f"Failed to send APK to {chat_id}: {e}")
        return False


# --- Handlers ---

dp = Dispatcher()


@dp.message(Command("start"))
async def cmd_start(message: types.Message):
    user = message.from_user
    is_new = add_subscriber(user)
    log.info(f"{'New' if is_new else 'Returning'} subscriber: {user.full_name} ({user.id})")

    await message.answer(WELCOME_TEXT, parse_mode=ParseMode.HTML, reply_markup=main_keyboard())

    if is_new:
        for admin_id in ADMIN_IDS:
            if admin_id != user.id:
                try:
                    await message.bot.send_message(
                        admin_id,
                        f"👤 Новый подписчик: {user.full_name} (@{user.username or '—'}, id={user.id})",
                    )
                except Exception:
                    pass


@dp.callback_query(F.data == "get_apk")
async def cb_get_apk(callback: CallbackQuery):
    await callback.answer()
    apk = get_stable_apk()
    if apk:
        await send_apk(callback.bot, callback.message.chat.id, apk, "📲 <b>CheburMail</b>")
    else:
        await callback.message.answer("⏳ APK пока не собран. Вы получите его при следующем релизе.")


@dp.callback_query(F.data == "about")
async def cb_about(callback: CallbackQuery):
    await callback.answer()
    try:
        await callback.message.edit_text(ABOUT_TEXT, parse_mode=ParseMode.HTML, reply_markup=back_keyboard())
    except Exception:
        await callback.message.answer(ABOUT_TEXT, parse_mode=ParseMode.HTML, reply_markup=back_keyboard())


@dp.callback_query(F.data == "install")
async def cb_install(callback: CallbackQuery):
    await callback.answer()
    try:
        await callback.message.edit_text(INSTALL_TEXT, parse_mode=ParseMode.HTML, reply_markup=apk_keyboard())
    except Exception:
        await callback.message.answer(INSTALL_TEXT, parse_mode=ParseMode.HTML, reply_markup=apk_keyboard())


@dp.callback_query(F.data == "feedback")
async def cb_feedback(callback: CallbackQuery):
    await callback.answer()
    try:
        await callback.message.edit_text(
            "💬 <b>Обратная связь</b>\n\nНапишите ваш вопрос или отзыв прямо сюда — разработчик получит и ответит.",
            parse_mode=ParseMode.HTML,
            reply_markup=back_keyboard(),
        )
    except Exception:
        await callback.message.answer(
            "💬 <b>Обратная связь</b>\n\nНапишите ваш вопрос или отзыв прямо сюда — разработчик получит и ответит.",
            parse_mode=ParseMode.HTML,
            reply_markup=back_keyboard(),
        )


@dp.callback_query(F.data == "main_menu")
async def cb_main_menu(callback: CallbackQuery):
    await callback.answer()
    try:
        await callback.message.edit_text(WELCOME_TEXT, parse_mode=ParseMode.HTML, reply_markup=main_keyboard())
    except Exception:
        await callback.message.answer(WELCOME_TEXT, parse_mode=ParseMode.HTML, reply_markup=main_keyboard())


# --- Свободные сообщения (не команды) → логируем + пересылаем админу ---

@dp.message(Command("push"))
async def cmd_push(message: types.Message):
    if message.from_user.id not in ADMIN_IDS:
        return
    apk = get_stable_apk()
    if not apk:
        await message.answer("❌ APK не найден")
        return
    subs = load_subscribers()
    ids = [int(k) for k in subs.keys()]
    await message.answer(f"📤 Рассылка APK {len(ids)} подписчикам...")
    ok, fail = 0, 0
    for cid in ids:
        if await send_apk(message.bot, cid, apk, "🆕 <b>Новая версия CheburMail!</b>"):
            ok += 1
        else:
            fail += 1
    await message.answer(f"✅ {ok} отправлено, ❌ {fail} ошибок")


@dp.message(Command("push_beta"))
async def cmd_push_beta(message: types.Message):
    if message.from_user.id not in ADMIN_IDS:
        return
    apk = APK_DEBUG if APK_DEBUG.exists() else None
    if not apk:
        await message.answer("❌ Debug APK не найден")
        return
    ids = list(BETA_IDS)
    ok, fail = 0, 0
    for cid in ids:
        if await send_apk(message.bot, cid, apk, "🔧 <b>Beta build</b>"):
            ok += 1
        else:
            fail += 1
    await message.answer(f"✅ {ok} отправлено, ❌ {fail} ошибок")


@dp.message(Command("subs"))
async def cmd_subs(message: types.Message):
    if message.from_user.id not in ADMIN_IDS:
        return
    subs = load_subscribers()
    if not subs:
        await message.answer("Подписчиков нет.")
        return
    lines = []
    for cid, info in subs.items():
        beta = " 🧪" if info.get("beta") else ""
        un = f"@{info['username']}" if info.get("username") else "—"
        lines.append(f"• {info['name']} ({un}){beta}")
    await message.answer(f"<b>Подписчики ({len(subs)}):</b>\n" + "\n".join(lines), parse_mode=ParseMode.HTML)


@dp.message()
async def catch_all(message: types.Message):
    """Все неизвестные сообщения — логируем и пересылаем админу."""
    user = message.from_user
    text = message.text or message.caption or "[медиа]"
    log_incoming(user, text)
    log.info(f"Message from {user.full_name} ({user.id}): {text[:100]}")

    # Пересылаем админу
    if user.id not in ADMIN_IDS:
        for admin_id in ADMIN_IDS:
            try:
                await message.forward(admin_id)
            except Exception:
                pass
        await message.answer(
            "✉️ Сообщение отправлено разработчику. Ответ придёт сюда.",
            reply_markup=main_keyboard(),
        )
    # Если пишет админ — можно ответить через reply (обработка ниже)


# --- Main ---

async def main():
    session = AiohttpSession(proxy=PROXY)
    bot = Bot(token=BOT_TOKEN, session=session)

    me = await bot.get_me()
    log.info(f"Bot started: @{me.username} ({me.full_name})")

    await dp.start_polling(bot)


if __name__ == "__main__":
    asyncio.run(main())
