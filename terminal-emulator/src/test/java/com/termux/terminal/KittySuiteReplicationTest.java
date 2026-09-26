package com.termux.terminal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Réplica byte a byte del test 5 de la suite de dispositivo (kgp-test.sh,
 * camino "Kgp") y de los tests 1-4 que lo preceden: {@code kgp_transmit_png}
 * con el chunking real de lib.sh ({@code KGP_CHUNK=4096}), {@code a=p,U=1} con
 * data vacía y la rejilla exacta de {@code kgp_placeholders} (SGR
 * {@code 38;5;<id>} + diacríticos de fila/columna completos).
 *
 * <p>Objetivo: reproducir localmente la cadena transmisión → placement virtual
 * → rejilla placeholder y comprobar que el emulator entrega los bytes intactos
 * y una rejilla decodificable. Si aquí pasa pero en el dispositivo el test 5
 * muestra solo glifos, el fallo está fuera de {@code terminal-emulator}
 * (render bitmap / entorno pty).</p>
 *
 * <p>Fidelidad: los {@code \n} de los scripts llegan al emulator como
 * {@code \r\n} (ONLCR en el pty). Los prints cosméticos (kgp_head/kgp_info) se
 * omiten — solo mueven el cursor, sin efecto sobre la cadena KGP — por lo que
 * la rejilla cae en filas distintas que en dispositivo (las aserciones la
 * buscan en toda la pantalla).</p>
 */
public class KittySuiteReplicationTest extends TerminalTestCase {

	/** {@code KGP_CHUNK} de lib.sh:7 — chunks base64 de 4096 (múltiplo de 4). */
	private static final int KGP_CHUNK = 4096;

	/** {@code U+10EEEE} como surrogate pair (los escapes unicode de Java son de 4 dígitos hex). */
	private static final String PH = "\uDBFB\uDEEE";

	// --- helpers que replican lib.sh byte a byte ---

	/**
	 * {@code kgp_transmit_png} (lib.sh:90-114): base64 {@code -w0} del archivo,
	 * troceado en {@link #KGP_CHUNK}; primer chunk con el control completo
	 * ({@code a=...,f=100,t=d,i=...,q=2,m=...}) y los siguientes con
	 * {@code m=0/1} solo.
	 *
	 * @param file   bytes del PNG
	 * @param imgId  {@code i=} de protocolo
	 * @param action {@code a=} — la suite usa {@code t} (test 5) o {@code T}
	 * @param extra  prefijo de control opcional ({@code C=1}) o vacío
	 * @return el stream APC completo listo para el emulator
	 */
	private static String kgpTransmitPng(byte[] file, int imgId, String action, String extra) {
		final String b64 = ImageBase64.encode(file);
		final String prefix = (extra == null || extra.isEmpty()) ? "" : extra + ",";
		final StringBuilder sb = new StringBuilder();
		final int len = b64.length();
		int offset = 0;
		boolean first = true;
		while (offset < len) {
			final String chunk = b64.substring(offset, Math.min(offset + KGP_CHUNK, len));
			offset += KGP_CHUNK;
			final int m = (offset >= len) ? 0 : 1;
			if (first) {
				sb.append("\033_G").append(prefix)
					.append("a=").append(action)
					.append(",f=100,t=d,i=").append(imgId)
					.append(",q=2,m=").append(m).append(';')
					.append(chunk).append("\033\\");
				first = false;
			} else {
				sb.append("\033_Gm=").append(m).append(';').append(chunk).append("\033\\");
			}
		}
		return sb.toString();
	}

	/**
	 * {@code kgp_transmit_rgba} (lib.sh:123-134): mismo troceado, control
	 * {@code a=...,f=32,s=W,v=H,t=d,i=...,q=2,m=...}.
	 *
	 * @param w      ancho en píxeles ({@code s=})
	 * @param h      alto en píxeles ({@code v=})
	 * @param b64    payload base64 ya codificado
	 * @param imgId  {@code i=} de protocolo
	 * @param action {@code a=}
	 * @param extra  prefijo de control opcional ({@code C=1}) o vacío
	 * @return el stream APC completo
	 */
	private static String kgpTransmitRgba(int w, int h, String b64, int imgId, String action, String extra) {
		final String prefix = (extra == null || extra.isEmpty()) ? "" : extra + ",";
		final StringBuilder sb = new StringBuilder();
		final int len = b64.length();
		int offset = 0;
		boolean first = true;
		while (offset < len) {
			final String chunk = b64.substring(offset, Math.min(offset + KGP_CHUNK, len));
			offset += KGP_CHUNK;
			final int m = (offset >= len) ? 0 : 1;
			if (first) {
				sb.append("\033_G").append(prefix)
					.append("a=").append(action)
					.append(",f=32,s=").append(w).append(",v=").append(h)
					.append(",t=d,i=").append(imgId)
					.append(",q=2,m=").append(m).append(';')
					.append(chunk).append("\033\\");
				first = false;
			} else {
				sb.append("\033_Gm=").append(m).append(';').append(chunk).append("\033\\");
			}
		}
		return sb.toString();
	}

	/**
	 * {@code kgp_diacritic} (lib.sh:138-149) — la tabla a mano de la suite
	 * (0..15). El test 5 solo usa índices 0..5 (filas 0-2, columnas 0-5), donde
	 * coincide con la tabla real de kitty; de 6 en adelante la suite difiere,
	 * pero ese rango no interviene aquí.
	 *
	 * @param n índice de diacrítico
	 * @return el code point como String
	 */
	private static String kgpDiacritic(int n) {
		final int cp;
		switch (n) {
			case 0: cp = 0x0305; break;
			case 1: cp = 0x030D; break;
			case 2: cp = 0x030E; break;
			case 3: cp = 0x0310; break;
			case 4: cp = 0x0312; break;
			case 5: cp = 0x033D; break;
			case 6: cp = 0x0329; break;
			case 7: cp = 0x0338; break;
			case 8: cp = 0x0342; break;
			case 9: cp = 0x0347; break;
			case 10: cp = 0x0348; break;
			case 11: cp = 0x0349; break;
			case 12: cp = 0x034A; break;
			case 13: cp = 0x034B; break;
			case 14: cp = 0x034C; break;
			case 15: cp = 0x034D; break;
			default: cp = 0x0305; break;
		}
		return new String(Character.toChars(cp));
	}

	/**
	 * {@code kgp_placeholders} (lib.sh:154-171): {@code ESC[38;5;id m}, rejilla
	 * de {@code U+10EEEE + fila + columna} por celda y {@code ESC[39m}. Los
	 * {@code \n} entre filas van como {@code \r\n} (ONLCR).
	 *
	 * @param imgId id de imagen (foreground 256-color)
	 * @param rows  filas de la rejilla
	 * @param cols  columnas de la rejilla
	 * @return la rejilla placeholder lista para el emulator
	 */
	private static String kgpPlaceholders(int imgId, int rows, int cols) {
		final StringBuilder sb = new StringBuilder();
		sb.append("\033[38;5;").append(imgId).append('m');
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				sb.append(PH).append(kgpDiacritic(r)).append(kgpDiacritic(c));
			}
			if (r < rows - 1) sb.append("\r\n");
		}
		sb.append("\033[39m");
		return sb.toString();
	}

	/**
	 * PNG sintético con firma + IHDR válidos ({@code sniffPngSize} solo lee
	 * eso) y relleno determinista hasta {@code totalBytes}. No es decodificable
	 * por BitmapFactory, pero estos tests no dibujan bitmaps.
	 *
	 * @param width      ancho IHDR
	 * @param height     alto IHDR
	 * @param totalBytes longitud total del array
	 * @return bytes del falso PNG
	 */
	private static byte[] fakePng(int width, int height, int totalBytes) {
		final byte[] b = new byte[Math.max(totalBytes, 33)];
		b[0] = (byte) 0x89; b[1] = 'P'; b[2] = 'N'; b[3] = 'G';
		b[4] = 13; b[5] = 10; b[6] = 26; b[7] = 10;
		b[8] = 0; b[9] = 0; b[10] = 0; b[11] = 13;
		b[12] = 'I'; b[13] = 'H'; b[14] = 'D'; b[15] = 'R';
		b[16] = (byte) (width >>> 24); b[17] = (byte) (width >>> 16);
		b[18] = (byte) (width >>> 8); b[19] = (byte) width;
		b[20] = (byte) (height >>> 24); b[21] = (byte) (height >>> 16);
		b[22] = (byte) (height >>> 8); b[23] = (byte) height;
		for (int i = 24; i < b.length; i++) b[i] = (byte) (i * 31 + 7);
		return b;
	}

	// --- aserciones ---

	private static final class Found {
		final int screenRow;
		final int column;
		final KittyPlaceholderDecoder.Target target;

		Found(int screenRow, int column, KittyPlaceholderDecoder.Target target) {
			this.screenRow = screenRow;
			this.column = column;
			this.target = target;
		}
	}

	/** Recorre todas las filas de la pantalla buscando celdas placeholder. */
	private List<Found> collectAll() {
		final List<Found> found = new ArrayList<>();
		final TerminalBuffer screen = mTerminal.getScreen();
		for (int row = 0; row < screen.mScreenRows; row++) {
			final int screenRow = row;
			final TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(screenRow));
			final KittyPlaceholderDecoder.RowState state = new KittyPlaceholderDecoder.RowState();
			KittyPlaceholderDecoder.collectRow(line, mTerminal.mColumns, state, (column, target) ->
				found.add(new Found(screenRow, column, target)));
		}
		return found;
	}

	/**
	 * El payload registrado debe ser byte a byte el original (ensamblaje de
	 * chunks + base64 decodificado).
	 *
	 * @param original bytes transmitidos
	 */
	private void assertPayloadIntact(byte[] original) {
		final KittyVirtualPlacement vp = mTerminal.resolveVirtualPlacement(5);
		assertNotNull("virtual placement i=5 no resuelve", vp);
		final TerminalImageData data = mTerminal.getImageData(vp.registryId);
		assertNotNull("image data del registry " + vp.registryId, data);
		assertEquals("payload tras chunking+base64",
			ImageBase64.encode(original), ImageBase64.encode(data.encoded));
	}

	/**
	 * La rejilla completa debe decodificarse: {@code rows*cols} celdas, todas
	 * con el id de protocolo, sin duplicados en la rejilla y cada celda en su
	 * columna de pantalla (rejilla emitida desde la columna 0 con ONLCR).
	 *
	 * @param protocolId {@code i=} transmitido (fg {@code 38;5;id})
	 * @param rows       filas de la rejilla
	 * @param cols       columnas de la rejilla
	 */
	private void assertGridDecodes(int protocolId, int rows, int cols) {
		final KittyVirtualPlacement vp = mTerminal.resolveVirtualPlacement(protocolId);
		assertNotNull("virtual placement i=" + protocolId + " no resuelve", vp);
		assertEquals("cols del placement", cols, vp.cols);
		assertEquals("rows del placement", rows, vp.rows);

		final List<Found> found = collectAll();
		assertEquals("celdas placeholder en pantalla", rows * cols, found.size());
		final Set<String> seen = new HashSet<>();
		for (Found f : found) {
			assertEquals("id en fila " + f.screenRow + ", col " + f.column,
				protocolId, f.target.imageId);
			assertTrue("gridRow fuera de rango: " + f.target.gridRow,
				f.target.gridRow >= 0 && f.target.gridRow < rows);
			assertTrue("gridCol fuera de rango: " + f.target.gridCol,
				f.target.gridCol >= 0 && f.target.gridCol < cols);
			assertTrue("celda de rejilla duplicada (" + f.target.gridRow + "," + f.target.gridCol + ")",
				seen.add(f.target.gridRow + ":" + f.target.gridCol));
			assertEquals("columna de pantalla != gridCol en fila " + f.screenRow,
				f.target.gridCol, f.column);
		}
	}

	// --- tests ---

	/**
	 * Test 5 aislado, variante multi-chunk: 5000 bytes → 6668 de base64 →
	 * 2 chunks con {@code KGP_CHUNK=4096}, igual que un PNG de ~5 KB en
	 * dispositivo.
	 */
	public void testTest5ChunkedTransmitThenVirtualPlace() {
		withTerminalSized(80, 24);
		final byte[] png = fakePng(320, 180, 5000);
		assertTrue("fixture debe partirse en >=2 chunks",
			ImageBase64.encode(png).length() > KGP_CHUNK);

		// kgp_transmit_png "$PH" 5 t ""  → q=2, sin respuesta
		enterString(kgpTransmitPng(png, 5, "t", ""));
		assertEquals("transmit q=2 debe ser silenciosa", "", mOutput.getOutputAndClear());

		// printf 'a=p,U=1,i=5,c=6,r=3,q=2;' — data vacía exactamente
		enterString("\033_Ga=p,U=1,i=5,c=6,r=3,q=2;\033\\");
		assertEquals("place q=2 debe ser silencioso", "", mOutput.getOutputAndClear());

		assertPayloadIntact(png);

		// printf '\n' + kgp_placeholders 5 3 6 + printf '\n\n' (con ONLCR)
		enterString("\r\n" + kgpPlaceholders(5, 3, 6) + "\r\n\r\n");
		assertGridDecodes(5, 3, 6);
	}

	/**
	 * Test 5 aislado, variante chunk único: el gradient-64 real (~1.5 KB →
	 * 2000 de base64) entra en un solo chunk con {@code m=0}.
	 */
	public void testTest5SingleChunkTransmitThenVirtualPlace() {
		withTerminalSized(80, 24);
		final byte[] png = fakePng(64, 64, 1500);
		assertTrue("fixture debe caber en 1 chunk",
			ImageBase64.encode(png).length() <= KGP_CHUNK);

		enterString(kgpTransmitPng(png, 5, "t", ""));
		assertEquals("", mOutput.getOutputAndClear());
		enterString("\033_Ga=p,U=1,i=5,c=6,r=3,q=2;\033\\");
		assertEquals("", mOutput.getOutputAndClear());

		assertPayloadIntact(png);
		enterString("\r\n" + kgpPlaceholders(5, 3, 6) + "\r\n\r\n");
		assertGridDecodes(5, 3, 6);
	}

	/**
	 * Secuencia completa del kgp-test.sh tests 1→5 en orden: query, RGBA con
	 * ack, PNG chunked con ack (el que sí funciona en dispositivo), PNG con alfa
	 * y por último el test 5. Caza interferencias entre tests (estado de chunks
	 * a medias, registry, placements) que el test aislado no vería.
	 */
	public void testSuiteSequenceTestsOneToFive() {
		withTerminalSized(80, 24);

		// 1) query: a=q + CSI c → i=31;OK y Device Attributes
		enterString("\033_Gi=31,s=1,v=1,a=q,t=d,f=24,q=0;AAAA\033\\\033[c");
		final String queryReply = mOutput.getOutputAndClear();
		assertTrue("query reply: " + escape(queryReply), queryReply.contains("\033_Gi=31;OK"));
		assertTrue("query DA: " + escape(queryReply), queryReply.contains("\033[?"));

		// 2) kgp_transmit_rgba 2 1 1 /wAA/w== T C=1 + a=p,i=2,q=0 → OK
		enterString(kgpTransmitRgba(1, 1, "/wAA/w==", 2, "T", "C=1"));
		enterString("\r\n");
		enterString("\033_Ga=p,i=2,q=0;\033\\");
		final String rgbaReply = mOutput.getOutputAndClear();
		assertTrue("ack i=2: " + escape(rgbaReply), rgbaReply.contains("\033_Gi=2;OK"));

		// 3) PNG chunked (demo 320×180, >4096 b64) + a=p,i=3,q=0 → OK
		final byte[] demo = fakePng(320, 180, 5000);
		enterString(kgpTransmitPng(demo, 3, "T", "C=1"));
		enterString("\r\n");
		enterString("\033_Ga=p,i=3,q=0;\033\\");
		final String pngReply = mOutput.getOutputAndClear();
		assertTrue("ack i=3: " + escape(pngReply), pngReply.contains("\033_Gi=3;OK"));

		// 4) PNG con alfa, sin ack (la suite solo lo envía)
		enterString(kgpTransmitPng(fakePng(64, 64, 1500), 4, "T", "C=1"));
		mOutput.getOutputAndClear();

		// 5) el test 5: t=d id=5 6×3, idéntico al aislado
		final byte[] gradient = fakePng(64, 64, 1500);
		enterString(kgpTransmitPng(gradient, 5, "t", ""));
		assertEquals("transmit test5 q=2 debe ser silenciosa", "", mOutput.getOutputAndClear());
		enterString("\033_Ga=p,U=1,i=5,c=6,r=3,q=2;\033\\");
		assertEquals("place test5 q=2 debe ser silencioso", "", mOutput.getOutputAndClear());
		assertPayloadIntact(gradient);
		enterString("\r\n" + kgpPlaceholders(5, 3, 6) + "\r\n\r\n");
		assertGridDecodes(5, 3, 6);
	}

	/** Escape para mensajes de fallo legibles (ESC visible como {@code ESC}). */
	private static String escape(String s) {
		return s.replace("\033", "ESC");
	}
}
