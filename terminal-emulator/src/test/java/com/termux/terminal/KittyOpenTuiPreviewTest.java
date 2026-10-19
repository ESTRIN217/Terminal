package com.termux.terminal;

import java.util.List;

/**
 * Regresión de la secuencia real de opencode/OpenTUI (capturada del TUI en un PTY): transmisión
 * PNG troceada ({@code a=t,f=100,m=1}), colocación con rectángulo de celdas y recorte de origen
 * ({@code a=p,c=,r=,x=,y=,w=,h=}) y borrado posterior ({@code a=d,d=I}).
 *
 * <p>Lo que rompía las previsualizaciones: OpenTUI genera ids de imagen y de colocación de
 * **32 bits sin signo** ({@code i=3487878681} en la captura) y el emulador los leía con
 * {@code Integer.parseInt}, que desborda y devuelve {@code 0}: la transmisión registraba el
 * payload sin asociarlo al {@code i=} del cliente y la colocación contestaba
 * {@code ENOENT:Image not found} (silenciada por {@code q=2}), así que el rectángulo nunca
 * llegaba a reservarse ni a pintarse y la TUI dejaba su caja vacía.</p>
 *
 * <p>La captura usa un PNG de 96×96 y un rectángulo de 14×7 celdas; aquí el payload se reduce al
 * PNG 2×2 de {@link KittyPlacementRunsTest} (crop {@code w=2,h=2}) para no arrastrar 24 KB de
 * base64 al test, pero la forma de los comandos, su orden y sus claves son los capturados.</p>
 */
public class KittyOpenTuiPreviewTest extends TerminalTestCase {

	/** PNG 2×2 (ajedrez rojo/azul); el emulador sondea 2×2 píxeles de su IHDR. */
	private static final String PNG_2x2_B64 =
		"iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEklEQVR4nGP4z8AAQkAMJEAsAEPOB/kTdAflAAAAAElFTkSuQmCC";

	/** {@code i=} de la captura: 3487878681 &gt; {@link Integer#MAX_VALUE}, válido en kitty. */
	private static final String OPENCODE_IMAGE_ID = "3487878681";

	/** El mayor id de 32 bits sin signo: 4294967295. */
	private static final String MAX_UNSIGNED_ID = "4294967295";

	/** {@code z=} de la captura: el centinela negativo que OpenTUI manda para pintar bajo el texto. */
	private static final int OPENCODE_Z = -1073741823;

	private static String kg(String control, String data) {
		return "\033_G" + control + ";" + data + "\033\\";
	}

	/** {@code a=t} troceado como lo manda OpenTUI: metadatos en el primer chunk, {@code m=} en los demás. */
	private static String transmitChunked(String imageId) {
		final int cut1 = PNG_2x2_B64.length() / 3;
		final int cut2 = 2 * cut1;
		return kg("a=t,f=100,i=" + imageId + ",m=1,q=2", PNG_2x2_B64.substring(0, cut1))
			+ kg("m=1,q=2", PNG_2x2_B64.substring(cut1, cut2))
			+ kg("m=0,q=2", PNG_2x2_B64.substring(cut2));
	}

	/** {@code a=p} con la forma exacta de la captura (celdas + recorte en píxeles + {@code C=1} + {@code z=}). */
	private static String placeOpenCodePreview(String imageId, String placementId, int q) {
		return kg("a=p,i=" + imageId + ",p=" + placementId
			+ ",c=14,r=7,x=0,y=0,w=2,h=2,C=1,z=" + OPENCODE_Z + ",q=" + q, "");
	}

	/** Coloca la previsualización de opencode en la celda indicada y devuelve su colocación. */
	private TerminalImagePlacement withOpenCodePreview(int row, int col) {
		withTerminalSized(30, 20);
		enterString(transmitChunked(OPENCODE_IMAGE_ID));
		enterString("\033[" + (row + 1) + ";" + (col + 1) + "H");
		enterString(placeOpenCodePreview(OPENCODE_IMAGE_ID, "1", 2));
		return mTerminal.getPlacementAt(row, col);
	}

	/**
	 * La secuencia capturada reserva y pinta: la colocación existe (celdas y recorte intactos), su
	 * payload sigue disponible para el pintor y cae en la pasada de debajo del texto ({@code z<0}).
	 */
	public void testOpenCodePreviewSequenceReservesAndPaints() {
		final TerminalImagePlacement placement = withOpenCodePreview(4, 6);
		assertNotNull("la colocación de la preview debe reservar la celda de origen", placement);
		assertEquals("c= son las celdas de destino", 14, placement.cellsW);
		assertEquals("r= son las celdas de destino", 7, placement.cellsH);
		assertEquals("x= es el origen del recorte", 0, placement.cropX);
		assertEquals("y= es el origen del recorte", 0, placement.cropY);
		assertEquals("w= es el ancho del recorte en píxeles", 2, placement.cropW);
		assertEquals("h= es el alto del recorte en píxeles", 2, placement.cropH);
		assertEquals("z= negativo pinta bajo el texto", OPENCODE_Z, placement.z);
		assertEquals("p= es la identidad de la colocación", 1, placement.placementId);
		assertEquals("el cursor no se mueve", 6, mTerminal.getCursorCol());
		assertEquals("el cursor no se mueve", 4, mTerminal.getCursorRow());

		final TerminalImageData data = mTerminal.getImageDataAt(4, 6);
		assertNotNull("el pintor necesita el payload de la colocación", data);
		assertEquals("los chunks se reensamblan en un solo payload", placement.registryId, data.id);
		assertEquals(2, data.pixelWidth);
		assertEquals(2, data.pixelHeight);

		final List<TerminalImagePlacement> belowText = mTerminal.getPlacementsForRow(4, true);
		assertEquals("una colocación de debajo del texto", 1, belowText.size());
		assertSame(placement, belowText.get(0));
		assertTrue("no se pinta en la pasada de encima del texto",
			mTerminal.getPlacementsForRow(4, false).isEmpty());
		assertEquals("cada celda del rectángulo queda reservada", placement.key, mTerminal.getPlacementKeyAt(4, 6));
		assertEquals(placement.key, mTerminal.getPlacementKeyAt(10, 19));
		assertEquals("fuera del rectángulo no reserva nada", 0, mTerminal.getPlacementKeyAt(4, 5));
	}

	/** La respuesta repite el id en su forma sin signo: el cliente no puede recibir un id negativo. */
	public void testLargeUnsignedImageIdReplyEchoesUnsignedForm() {
		withTerminalSized(30, 20);
		enterString(kg("a=q,i=" + OPENCODE_IMAGE_ID + ",s=1,v=1,f=24,q=0", "AAAA"));
		assertEquals("\033_Gi=" + OPENCODE_IMAGE_ID + ";OK\033\\", mOutput.getOutputAndClear());

		enterString(transmitChunked(OPENCODE_IMAGE_ID));
		enterString("\033[1;1H");
		enterString(placeOpenCodePreview(OPENCODE_IMAGE_ID, MAX_UNSIGNED_ID, 0));
		assertEquals("\033_Gi=" + OPENCODE_IMAGE_ID + ",p=" + MAX_UNSIGNED_ID + ";OK\033\\",
			mOutput.getOutputAndClear());
	}

	/** Un {@code p=} sin signo grande identifica la colocación: repetirlo la reemplaza, no la duplica. */
	public void testLargeUnsignedPlacementIdKeepsPlacementIdentity() {
		withTerminalSized(30, 20);
		enterString(transmitChunked(OPENCODE_IMAGE_ID));
		enterString("\033[3;4H");
		enterString(placeOpenCodePreview(OPENCODE_IMAGE_ID, MAX_UNSIGNED_ID, 2));
		final TerminalImagePlacement first = mTerminal.getPlacementAt(2, 3);
		assertNotNull("la primera colocación debe existir", first);

		enterString("\033[9;11H");
		enterString(placeOpenCodePreview(OPENCODE_IMAGE_ID, MAX_UNSIGNED_ID, 2));
		final TerminalImagePlacement second = mTerminal.getPlacementAt(8, 10);
		assertNotNull("repetir el p= reco coloca la misma colocación", second);
		assertEquals("misma clave de colocación", first.key, second.key);
		assertNull("la celda vieja se libera", mTerminal.getPlacementAt(2, 3));
		assertEquals("sigue habiendo una sola colocación de la imagen",
			1, mTerminal.getPlacementsForImage(first.registryId).size());
	}

	/** {@code a=d,d=i} con un id sin signo grande también tiene que encontrar la imagen. */
	public void testLargeUnsignedImageIdDeleteReleasesPayloadAndPlacements() {
		final TerminalImagePlacement placement = withOpenCodePreview(4, 6);
		assertNotNull(placement);

		enterString(kg("a=d,d=i,i=" + OPENCODE_IMAGE_ID + ",q=2", ""));
		assertNull("la colocación desaparece", mTerminal.getPlacementAt(4, 6));
		assertEquals("la celda queda libre", 0, mTerminal.getPlacementKeyAt(4, 6));
		assertNull("el payload se libera", mTerminal.getImageData(placement.registryId));
	}
}
