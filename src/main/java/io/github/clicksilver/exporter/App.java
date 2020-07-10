package io.github.clicksilver.exporter;

import java.time.*;
import java.time.format.*;
import java.io.IOException;
import java.lang.String;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.Arrays;
import javax.swing.JFrame;
import javax.swing.JOptionPane;

import io.github.legendff.mhw.save.Savecrypt;

public class App {

  // Decoration item IDs
  static final int kMinJewelId = 727;
  static final int kMaxJewelId = 2272;
  static final int kNumDecos = kMaxJewelId - kMinJewelId + 1;

  // 10 pages, 50 jewels per page
  static final int kDecoInventorySize = 50 * 10;

  // 8 bytes per jewel, (4 for ID + 4 for count)
  static final int kNumBytesPerDeco = 8;

  // direct offsets into the decrypted save, where each decorations list starts
  static final int kSaveSlotDecosOffsets[] = new int[]{4302696, 6439464, 8576232};

  static final DateTimeFormatter formatter =  DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

  public static void main(String[] args) {
    if (args.length == 0) {
      JFrame frame = new JFrame();
      JOptionPane.showMessageDialog(frame, "No input save file detected.\n\n" +
                                    "Drag save file onto the executable.",
                                    "ERROR", JOptionPane.INFORMATION_MESSAGE);
      System.exit(0);
    }
    byte[] bytes;
    Path p = Paths.get(args[0]);
    boolean japanese = (args.length >= 2 && args[1].trim().endsWith("japanese"));
    try {
      byte[] save = Files.readAllBytes(p);
      byte[] decrypted_save = Savecrypt.decryptSave(save);

      System.out.println("WARNING: Unequip all decorations before using this otherwise the count will be wrong.");
      int i = 0;
      for (int offset : kSaveSlotDecosOffsets) {
        ++i;
        // Get actual decoration counts from the decrypted save.
        int[] decorationCounts = getJewelCounts(decrypted_save, offset);

        // Write out the Honeyhunter format.
        if (decorationCounts != null) {
          String time = formatter.format(LocalDateTime.now());
          writeTo(String.format("honeyhunter-%s-%d.json", time, i), outputHoneyHunter(decorationCounts), "");
          writeTo(String.format("mhw-wiki-db-%s-%d.json", time, i), outputWikiDB(decorationCounts, japanese), "");
        }
      }

      // JFrame frame = new JFrame();
      // JOptionPane.showMessageDialog(frame, "Successfully exported decorations",
      //     "COMPLETE", JOptionPane.INFORMATION_MESSAGE);
      System.out.println("Successfully exported decorations");
      System.exit(0);
    } catch(Exception e) {
      e.printStackTrace();
      // JFrame frame = new JFrame();
      // JOptionPane.showMessageDialog(frame, "Not a valid save file.", "ERROR",
      //     JOptionPane.INFORMATION_MESSAGE);
      System.out.println("Failed to export decorations");
      System.exit(0);
    }
    return;
  }

  private static void writeTo(String path, CharSequence... lines) throws IOException {
    Files.write(
      Paths.get(path),
      Arrays.asList(lines),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
    );
  }

  public static void printJewels(int[] counts) {
    for (int i=0; i<counts.length; ++i) {
      String name = DecorationNames.getDecorationName(i + kMinJewelId);
      int count = counts[i];
      if(name.length() != 0 && count != 0) {
        System.out.println(name + ": " + counts[i]);
      }
    }
  }

  private static final boolean PRETTY_PRINT = false;

  public static String outputWikiDB(int[] counts, boolean useJapanese) {
    int[] wikiDBcounts = new int[WikiDB.kNumDecos];
    Arrays.fill(wikiDBcounts, 0);

    for (int i=0; i<counts.length; ++i) {
      String name = DecorationNames.getDecorationName(i + kMinJewelId);
      if (name.length() == 0) {
        continue;
      }
      int order = WikiDB.getOrderingFromName(name);
      if (order < 0) {
        continue;
      }
      wikiDBcounts[order] = counts[i];
    }

    StringBuilder contents = new StringBuilder();
    contents.append('{');
    for (int i=0; i<wikiDBcounts.length; ++i) {
      if (i != 0)
        contents.append(',');
      if (PRETTY_PRINT) contents.append('\n');
      int count = Math.max(0, wikiDBcounts[i]);
      if (!PRETTY_PRINT) count = Math.min(count, 7);
      if (PRETTY_PRINT) contents.append(' ').append(' ');
      contents.append('"');
      contents.append((useJapanese ? WikiDB.kDecoJapaneseNames : WikiDB.kDecoNames)[i]);
      contents.append('"');
      contents.append(':');
      if (PRETTY_PRINT) contents.append(' ');
      contents.append(count);
    }
    if (PRETTY_PRINT) contents.append('\n');
    contents.append('}');
    return contents.toString();
  }

  public static String outputHoneyHunter(int[] counts) {
    int[] hhCounts = new int[HoneyHunter.kNumDecos];
    Arrays.fill(hhCounts, 0);

    for (int i = 0; i < counts.length; ++i) {
      String name = DecorationNames.getDecorationName(i + kMinJewelId);
      if (name.length() == 0) {
        continue;
      }
      int count = Math.min(counts[i], HoneyHunter.getMaxCountFromName(name));
      int order = HoneyHunter.getOrderingFromName(name);
      if (order < 0 || count < 0) {
        continue;
      }
      hhCounts[order] = count;
    }

    StringBuilder contents = new StringBuilder();
    for (int i = 0; i < hhCounts.length; ++i) {
      if (i != 0)
        contents.append(',');
      contents.append(hhCounts[i]);
    }
    return contents.toString();
  }

  public static int[] getJewelCounts(byte[] bytes, int offset) {
    int[] counts = new int[kNumDecos];

    ByteBuffer buf = ByteBuffer.wrap(bytes, offset, kDecoInventorySize * kNumBytesPerDeco);

    // NOTE: Java is dumb about bytes.
    buf.order(ByteOrder.LITTLE_ENDIAN);

    boolean anyNonZero = false;

    for (int i=0; i<kDecoInventorySize; i++) {
      int jewelId = buf.getInt();
      int jewelCount = buf.getInt();
      if(jewelId == 0) {
        // missing owned deco, which is not an invalid deco
        continue;
      }
      if (jewelId < kMinJewelId || jewelId > kMaxJewelId) {
        System.out.println("Error parsing decorations. Index=" + i +
            " ID=" + jewelId +
            " Count=" + jewelCount);
        return null;
      }

      if (jewelCount > 0) {
        anyNonZero = true;
      }

      counts[jewelId - kMinJewelId] = jewelCount;
    }

    if (anyNonZero) {
      return counts;
    }
    return null;
  }
}
