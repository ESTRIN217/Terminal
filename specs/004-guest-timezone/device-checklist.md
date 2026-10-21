# Checklist de dispositivo — Spec 004

1. Abrir la app y arrancar una sesión de Debian.
2. `echo $TZ` → ID de la zona del dispositivo (p. ej. `America/Caracas`).
3. `date` → fecha de hoy y hora local (no UTC; diferencia de -4 h para Caracas).
4. `ls -l /etc/localtime` → comprobar veredicto (puede ser UTC del rootfs; con
   `TZ` puesto no debe afectar a `date`).
5. Cambiar la zona del sistema en Ajustes (p. ej. a `America/New_York`), cerrar
   la sesión y abrir otra → `date` debe seguir a la nueva zona.
6. `sudo -i` innecesario (se entra como root); comprobar que un editor del file
   manager (nano) arranca igual que antes.
7. En el terminal del host (`failsafe`/$PREFIX), nada cambió: `echo $TZ`
   desajustado es lo esperado.
