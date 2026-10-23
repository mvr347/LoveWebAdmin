package me.lovelace.loveWebAdmin.utils;

import java.util.concurrent.TimeUnit;

/**
 * Счётчик работы плагина, которая идёт вне главного потока, с возможностью дождаться её окончания
 * перед выключением. Нужен, чтобы выключаемый плагин не выгружал jar и не закрывал БД, пока
 * фоновый поток ещё читает классы или пишет в базу.
 *
 * <p>{@link #enter()} возвращает {@code false} после {@link #stopAndAwait}, поэтому новая работа
 * не начинается, пока плагин гаснет. {@link #reopen()} снимает остановку для повторного запуска.</p>
 */
public final class RunGuard {

    private int active;
    private boolean stopped;

    /** Регистрирует начало работы; {@code false}, если плагин уже останавливается. */
    public synchronized boolean enter() {
        if (stopped) return false;
        active++;
        return true;
    }

    /** Регистрирует окончание работы, начатой через {@link #enter()}. */
    public synchronized void exit() {
        active--;
        if (active == 0) notifyAll();
    }

    /**
     * Запрещает новую работу и ждёт, пока уже начатая завершится.
     *
     * @return {@code true}, если вся работа завершилась за отведённое время
     */
    public synchronized boolean stopAndAwait(long timeout, TimeUnit unit) {
        stopped = true;
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (active > 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Снимает остановку, чтобы объект можно было использовать снова после {@link #stopAndAwait}. */
    public synchronized void reopen() {
        stopped = false;
    }
}
